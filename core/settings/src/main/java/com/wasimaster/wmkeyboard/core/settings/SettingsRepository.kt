package com.wasimaster.wmkeyboard.core.settings

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.endpoints.RepoLocation
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.endpoints.repoLocationFromFields
import com.wasimaster.wmkeyboard.core.endpoints.toFields
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.settings.sink.S3Sink
import com.wasimaster.wmkeyboard.core.addons.AddonStore
import com.wasimaster.wmkeyboard.core.clipboard.ClipboardStore
import com.wasimaster.wmkeyboard.core.clipboard.PhoneFormats
import com.wasimaster.wmkeyboard.core.selection.SelectionMacro
import com.wasimaster.wmkeyboard.core.selection.SelectionMacroCodec
import com.wasimaster.wmkeyboard.core.selection.SelectionMacros
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.icons.IconOverrides
import com.wasimaster.wmkeyboard.core.icons.IconPackStore
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryCatalog
import com.wasimaster.wmkeyboard.core.tools.QrCodeGen
import com.wasimaster.wmkeyboard.core.tools.rememberLayoutSwitch
import com.wasimaster.wmkeyboard.core.input.composer.DoublePinyinScheme
import com.wasimaster.wmkeyboard.core.input.composer.HanVariant
import com.wasimaster.wmkeyboard.core.input.composer.PinyinFuzzy
import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideShapeStore
import com.wasimaster.wmkeyboard.core.prediction.CustomDictionaries
import com.wasimaster.wmkeyboard.core.prediction.OctopusKind
import com.wasimaster.wmkeyboard.core.prediction.PhoneticStripSource
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.UndoMemory
import com.wasimaster.wmkeyboard.prediction.R as PredictionR
import com.wasimaster.wmkeyboard.core.snippets.MultiExpandMode
import com.wasimaster.wmkeyboard.core.layout.AlternateColumnsRange
import com.wasimaster.wmkeyboard.core.layout.isShippedLayoutId
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.LayoutCodec
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutCodec
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec
import com.wasimaster.wmkeyboard.core.layout.resolvePanelLayout
import com.wasimaster.wmkeyboard.core.layout.resolvePanelLayouts
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.repair
import com.wasimaster.wmkeyboard.core.layout.resolveLayoutSelection
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.layout.script
import com.wasimaster.wmkeyboard.core.tools.AltCalendar
import com.wasimaster.wmkeyboard.core.tools.CurrencyClient
import com.wasimaster.wmkeyboard.core.tools.DictionarySourceChoice
import com.wasimaster.wmkeyboard.core.tools.DictionarySources
import com.wasimaster.wmkeyboard.core.tools.CurrencyLabel
import com.wasimaster.wmkeyboard.core.tools.SolarTimes
import com.wasimaster.wmkeyboard.core.tools.Weekend
import com.wasimaster.wmkeyboard.core.tools.defaultAltCalendars
import com.wasimaster.wmkeyboard.core.tools.isSouthernHemisphere
import com.wasimaster.wmkeyboard.core.script.ComposerType
import com.wasimaster.wmkeyboard.core.script.DeviceLocales
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.NumeralCommitScope
import com.wasimaster.wmkeyboard.core.script.NumeralSystem
import com.wasimaster.wmkeyboard.core.script.RomanizedPairing
import com.wasimaster.wmkeyboard.core.script.ScriptDef
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import android.util.Base64
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.theme.DEFAULT_THEME_ID
import com.wasimaster.wmkeyboard.core.theme.KeyShapeKind
import com.wasimaster.wmkeyboard.core.theme.PhotoAttribution
import com.wasimaster.wmkeyboard.core.theme.ThemeCodec
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.findThemeFamily
import com.wasimaster.wmkeyboard.core.theme.replacingMember
import com.wasimaster.wmkeyboard.core.theme.selfAndVariants
import com.wasimaster.wmkeyboard.core.theme.withEmbeddedImages
import com.wasimaster.wmkeyboard.core.theme.withExtractedImages
import com.wasimaster.wmkeyboard.core.aihistory.AiHistoryStore
import com.wasimaster.wmkeyboard.core.tools.AiActionCodec
import com.wasimaster.wmkeyboard.core.tools.AiActionSpec
import com.wasimaster.wmkeyboard.core.tools.BuiltInAiActions
import com.wasimaster.wmkeyboard.core.tools.BuiltInSymbolSets
import com.wasimaster.wmkeyboard.core.tools.TypingStats
import com.wasimaster.wmkeyboard.core.tools.mergeLegacyAiPrompts
import com.wasimaster.wmkeyboard.core.tools.DefaultToolLetters
import com.wasimaster.wmkeyboard.core.tools.TypingAchievements
import com.wasimaster.wmkeyboard.core.tools.decodeToolLetters
import com.wasimaster.wmkeyboard.core.tools.encodeToolLetters
import com.wasimaster.wmkeyboard.core.tools.formatLeader
import com.wasimaster.wmkeyboard.core.tools.parseLeader
import com.wasimaster.wmkeyboard.core.tools.SmartSuggest
import com.wasimaster.wmkeyboard.core.tools.SymbolSet
import com.wasimaster.wmkeyboard.core.tools.SymbolSetCodec
import com.wasimaster.wmkeyboard.core.tools.sanitizeSymbolPopups
import com.wasimaster.wmkeyboard.core.tools.TypingTestMode
import com.wasimaster.wmkeyboard.core.util.runCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.random.Random
import com.wasimaster.wmkeyboard.settings.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.thesaurus.SynonymSourceChoice
import com.wasimaster.wmkeyboard.core.thesaurus.SynonymSources
import com.wasimaster.wmkeyboard.core.vocab.VocabAccent
import com.wasimaster.wmkeyboard.core.vocab.VocabAudioSource
import com.wasimaster.wmkeyboard.core.vocab.VocabChipTap
import com.wasimaster.wmkeyboard.core.vocab.VocabCooldown
import com.wasimaster.wmkeyboard.core.vocab.VocabNudgeLevel
import com.wasimaster.wmkeyboard.core.vocab.VocabNudgeScope
import com.wasimaster.wmkeyboard.core.vocab.VocabRelatedTap
import com.wasimaster.wmkeyboard.core.vocab.VocabScheduler
import com.wasimaster.wmkeyboard.core.vocab.VocabWordInterval
import com.wasimaster.wmkeyboard.core.vocab.VocabProgress
import com.wasimaster.wmkeyboard.core.vocab.VocabPacks
import com.wasimaster.wmkeyboard.core.vocab.VocabPackFile

/** Visual theme for the keyboard and settings app. */
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/** What decides which half of an [AutoThemeSettings] pair is showing. */
enum class AutoThemeTrigger {
    /** The system's own light/dark setting. */
    SYSTEM,

    /** A clock schedule the user sets: light from one time, dark from another. */
    SCHEDULE,

    /**
     * The actual sun at the weather tool's saved location — light between
     * sunrise and sunset. Falls back to [SYSTEM] when no location is saved, and
     * on the polar days when the sun does not rise or set at all.
     */
    SUN,
    ;

    /** Caption for this choice; resolve it where it is drawn. */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            SYSTEM -> R.string.core_settings_auto_theme_trigger_system_label
            SCHEDULE -> R.string.core_settings_auto_theme_trigger_schedule_label
            SUN -> R.string.core_settings_auto_theme_trigger_sun_label
        }
}

/**
 * A pair of themes that swap over on their own: [lightThemeId] by day,
 * [darkThemeId] by night, with [trigger] deciding which of the two it is. When
 * [enabled], this takes over from [KeyboardSettings.keyboardThemeId] entirely —
 * the theme tool shows the active one but can't change it.
 *
 * Either half can hold one theme or a set to select from at random: see
 * [lightRandom] and `slotThemeId` in AutoThemeShuffle.kt.
 *
 * The ids are the same namespace as [KeyboardSettings.keyboardThemeId]:
 * [DEFAULT_THEME_ID], a built-in id, or a custom id. A nested class rather than
 * flat fields because the top-level [KeyboardSettings] count is near the JVM
 * `copy` ceiling; the DataStore keys stay flat all the same.
 */
data class AutoThemeSettings(
    val enabled: Boolean = false,
    val lightThemeId: String = DEFAULT_THEME_ID,
    val darkThemeId: String = DEFAULT_THEME_ID,
    val trigger: AutoThemeTrigger = AutoThemeTrigger.SYSTEM,
    /** Minutes past midnight the light theme takes over ([AutoThemeTrigger.SCHEDULE]). */
    val dayStartMinutes: Int = 7 * 60,
    /** Minutes past midnight the dark theme takes over ([AutoThemeTrigger.SCHEDULE]). */
    val nightStartMinutes: Int = 19 * 60,
    /**
     * Whether the light half selects from [lightPoolIds] instead of showing
     * [lightThemeId].
     *
     * A flag of its own rather than "the pool is not empty", so turning the
     * random slot off and on again keeps the set the user assembled, and
     * [lightThemeId] keeps the one theme they had before.
     */
    val lightRandom: Boolean = false,
    val darkRandom: Boolean = false,
    /** Theme ids the light half selects from while [lightRandom]. */
    val lightPoolIds: Set<String> = emptySet(),
    val darkPoolIds: Set<String> = emptySet(),
    /** How often a random half selects a new theme. */
    val shuffleInterval: RotationInterval = RotationInterval.EVERY_OPEN,
    /**
     * The id the light half is showing now, rewritten when [shuffleInterval]
     * comes due. Blank until the first selection.
     *
     * Stored rather than computed so `effectiveThemeId` stays a pure read: the
     * keyboard resolves its theme on the typing hot path and in three places
     * that are not composables, and none of them can own a random seed.
     */
    val shuffleLightId: String = "",
    val shuffleDarkId: String = "",
    /** Wall clock of the last selection, which survives a reboot but can jump. */
    val shuffledAtEpochMs: Long = 0L,
    /** Monotonic clock of the last selection, which cannot jump but restarts. */
    val shuffledAtElapsedMs: Long = 0L,
) {

    /**
     * Whether the dark half of the pair is the one that should be showing.
     *
     * [minutesOfDay] is the local clock as minutes past midnight and [sun] the
     * day's sunrise/sunset in the same units; both are passed in rather than
     * read here so this stays a pure function the tests can walk a day through.
     * A null [sun] under [AutoThemeTrigger.SUN] means no saved location or a
     * polar day, and falls back to [systemDark] — a theme that cannot be
     * decided is better decided by the system than left stuck on one half.
     */
    fun usesDarkSlot(
        systemDark: Boolean,
        minutesOfDay: Int,
        sun: SolarTimes? = null,
    ): Boolean = when (trigger) {
        AutoThemeTrigger.SYSTEM -> systemDark
        AutoThemeTrigger.SCHEDULE ->
            !isBetween(minutesOfDay, dayStartMinutes, nightStartMinutes)
        AutoThemeTrigger.SUN ->
            if (sun == null) systemDark
            else !isBetween(minutesOfDay, sun.sunriseMinutes, sun.sunsetMinutes)
    }

    /**
     * Whether [minute] falls in the half-open window `[start, end)`, wrapping
     * over midnight when the window does — which is what a "day" from 19:00 to
     * 07:00 is, and what someone on a night shift will actually set.
     */
    private fun isBetween(minute: Int, start: Int, end: Int): Boolean =
        if (start <= end) minute in start until end else minute >= start || minute < end
}

/**
 * The key-preview bubble (the character that pops above a pressed key). Grouped
 * into a nested class to keep [KeyboardSettings]'s top-level field count under
 * the JVM `copy$default` ceiling; the DataStore keys stay flat. Read as
 * `settings.popup.enabled` etc.
 */
data class KeyPopupSettings(
    val enabled: Boolean = true,
    /**
     * How long the bubble lingers *after* release, so a fast tap still leaves
     * a readable bubble instead of a single-frame flash. This is a comfort
     * floor: raise it for a slower, more deliberate feel.
     */
    val minDurationMs: Int = 140,
    /**
     * Hard ceiling on the bubble's on-screen life, measured from the press —
     * a stuck-bubble backstop, not a comfort knob. Normally the bubble clears
     * on release; if the release is ever dropped under UI-thread lag (e.g. the
     * InputConnection work on a new line), this cap hides it anyway so it can't
     * strand. Kept above the long-press timeout so genuine holds still preview
     * until the alternates popup takes over.
     */
    val maxDurationMs: Int = 750,
    val onKey: Boolean = true,
    val fontScale: Float = 1.0f,
    /**
     * Height of the bubble in the mode that [onKey] selects — [onKeyHeightDp]
     * or [floatingHeightDp], resolved by the repository so a reader that wants
     * "the bubble's height right now" has it in one field. The two styles want
     * very different numbers — an on-key bubble is measured from the bottom of
     * the key it covers, so most of its height is spent climbing back out from
     * under the finger, while a floating bubble already starts above the key and
     * only has to hold one character. Each style keeps its own stored value, so
     * a slider drag in one mode does not resize the other; [heightFor] reads
     * either one, for the callers that size a style the setting is not in.
     */
    val heightDp: Int = 110,
    /** The on-key bubble's stored height, whichever style is on; see [heightDp]. */
    val onKeyHeightDp: Int = 110,
    /** The floating bubble's stored height, whichever style is on; see [heightDp]. */
    val floatingHeightDp: Int = 65,
    /**
     * How far above the key a floating bubble sits, when [onKey] is off.
     *
     * The default is the gap the bubble always kept. Raising it lifts the
     * bubble clear of a finger that was covering it, which is the whole point
     * of the setting; the overlay grows its headroom by the same amount, so the
     * top row's bubble is still drawn in full rather than clipped.
     *
     * On-key bubbles ignore it. That style is anchored to the key's own bottom
     * edge and climbs out from under the finger by being tall, which is what
     * [heightDp] already controls.
     */
    val floatingOffsetYDp: Int = 10,
    /**
     * Sideways shift of a floating bubble, positive to the right.
     *
     * Screen-space rather than start-relative: this is about which hand holds
     * the phone, not which way the script runs, so an RTL layout does not
     * mirror it. The bubble stays clamped inside the keyboard, so a shift near
     * an edge stops at the edge instead of walking off screen.
     */
    val floatingOffsetXDp: Int = 0,
    /**
     * Whether the bubble also shows on the numeric keypads (number, phone,
     * date and time fields). Off by default: on a PIN-style pad the floating
     * character is noise at best and shoulder-surfable at worst.
     */
    val inNumericFields: Boolean = false,
    /**
     * Corner radius of every popup surface — the preview bubble, the long-press
     * alternates, the language picker and the panel menus. Read by the [shape]
     * kinds that follow a radius (rounded and cut); the rest size their corners
     * off the popup itself. A theme may override it
     * ([ThemeSpec.popupCornerRadiusDp]); this is the global it falls back to.
     */
    val cornerRadiusDp: Int = 12,
    /**
     * Outline every popup surface is drawn with. Shares the key shapes so a
     * popup can be squared off, a squircle or a full circle without a second
     * set of shape definitions; a theme may override it ([ThemeSpec.popupShape]).
     */
    val shape: KeyShapeKind = KeyShapeKind.ROUNDED,
    /**
     * Bubble background, as ARGB. Null follows the theme's popup colour, which
     * is the default; a colour set here wins over the theme, being the more
     * explicit of the two, and a per-key style still wins over both.
     *
     * Only the preview bubble reads it. The alternates, the language picker and
     * the panel menus stay on the theme, so a colour picked for a
     * one-character bubble cannot repaint every menu in the keyboard.
     */
    val backgroundColor: Long? = null,
    /** Bubble label colour, as ARGB; null follows the theme. See [backgroundColor]. */
    val textColor: Long? = null,
    /**
     * Text size of the long-press alternates ("more keys"), as a multiplier.
     *
     * Its own value rather than [fontScale], which it used to borrow. The two
     * popups are sized against different things: the preview bubble holds one
     * character inside a fixed [heightDp] and stops reading once the glyph
     * outgrows it, while the alternates popup is a grid that measures itself and
     * can keep growing. Sharing one slider capped the alternates at whatever the
     * bubble could survive, which is the complaint in issue #64 — the largest
     * setting was still tight on a Pixel 6a. So this one reaches twice as far,
     * and the bubble keeps its own range.
     *
     * A board that had already raised [fontScale] inherits that value here (the
     * repository falls back to the old key), so nothing shrinks on upgrade.
     */
    val alternatesFontScale: Float = 1.0f,
    /**
     * Space around each alternate inside the popup, in dp. The touch target and
     * the gap between neighbours are the same number: a tight popup is one where
     * the characters crowd each other *and* where the wrong one is easy to hit,
     * and one knob fixes both.
     */
    val alternatesPaddingDp: Int = 10,
    /**
     * How many alternates the popup puts on a row, or 0 for as many as fit.
     *
     * Auto (0) is the wrap that shipped: entries run left to right until the next
     * one would leave the display, which packs the popup tightest but makes its
     * shape depend on how wide each character happens to draw. A fixed 3..11 lays
     * every row on one column grid, so a key with many alternates reads as a
     * block and its entries land in the same place each time.
     */
    val alternatesColumns: Int = 0,
    /**
     * Whether the first alternate sits on the row nearest the key.
     *
     * Off (the default, and what shipped) fills the popup like a paragraph: the
     * first alternate is top left, and on a wrapped popup it is the one furthest
     * from the finger. On fills from the key outward, so the first alternate is
     * the closest and the overflow climbs away from it — what AOSP boards do, and
     * the shorter reach on a key with a dozen alternates (issue #64).
     */
    val alternatesNearestFirst: Boolean = false,
    /**
     * Whether the finger that opened the popup keeps choosing inside it: the
     * first alternate is highlighted as the popup appears, sliding the finger
     * moves the highlight, and letting go commits what is highlighted.
     *
     * On by default, which is what every stock keyboard does and what a press
     * and hold is expected to feel like: hold, glance, let go. Off is the
     * behaviour that shipped before, where the popup stays up after the finger
     * leaves and a second tap picks an entry.
     */
    val alternatesHoldToSelect: Boolean = true,
) {
    /**
     * The stored height of one style, on or off: what [heightDp] is for [onKey],
     * for a caller sizing a style the setting is not currently in (a theme that
     * pins its own placement).
     */
    fun heightFor(onKey: Boolean): Int = if (onKey) onKeyHeightDp else floatingHeightDp
}

/**
 * Hold-to-repeat cadence, per key.
 *
 * Backspace and space are the two keys that repeat under a held finger by
 * themselves, and they are held for opposite reasons: a fast backspace clears a
 * line in one hold, while a fast spacebar runs away and has to be undone. So
 * each keeps its own interval rather than sharing one "key repeat" slider. A key
 * an author has turned "Repeat while held" on has a third ([customKeyMs]).
 *
 * Nested to keep [KeyboardSettings]'s top-level field count under the JVM
 * `copy$default` ceiling; the DataStore keys stay flat.
 */
data class KeyRepeatSettings(
    /** Backspace and forward-delete, including the panel backspaces. */
    val deleteMs: Int = 50,
    /**
     * The same hold when [TextEditingSettings.deleteHoldDeletesWords] turns it
     * into a word clear (issue #216).
     *
     * Slower than [deleteMs], and its own number rather than a multiple of it,
     * because the two are held for different lengths of time: 50 ms a
     * character is a second per line, while 50 ms a *word* empties a paragraph
     * before a finger can lift. The default is roughly a word every seventh of
     * a second — fast enough to be worth holding, slow enough to watch.
     */
    val wordDeleteMs: Int = 140,
    /**
     * The spacebar, at half the cadence of backspace. A held space overshoots
     * in a way a held backspace does not: the extra spaces are invisible until
     * the word after them lands in the wrong place.
     */
    val spaceMs: Int = 100,
    /**
     * Any key a layout turned `Key.repeatOnHold` on — the arrow row issue #231
     * asked for, and whatever else somebody builds with it.
     *
     * One number for all of them rather than one per action: the keys this
     * covers are the author's own, and the thing they have in common is that the
     * author wants them to walk under a thumb. Backspace's cadence is the right
     * starting point — an arrow key and a delete are held for the same "keep
     * going until I see what I want" reason — so it starts at the same 50 ms.
     */
    val customKeyMs: Int = 50,
    /**
     * How long a key is held before it starts repeating.
     *
     * Its own value rather than [KeyboardSettings.longPressDelayMs], which is
     * what it used to borrow. That delay is how long an accent popup takes to
     * appear, and someone who lengthens it for a tremor was lengthening the
     * wait before backspace starts clearing too — two unrelated problems
     * sharing one number.
     */
    val startDelayMs: Int = 300,
)

/** Shrinks the keyboard toward one edge for thumb reach. */
enum class OneHandedMode { OFF, LEFT, RIGHT }

/** Which edge a one-handed keyboard docks to. */
enum class OneHandedSide {
    LEFT, RIGHT;

    /** The live [OneHandedMode] that renders on this side. */
    fun toMode(): OneHandedMode = if (this == LEFT) OneHandedMode.LEFT else OneHandedMode.RIGHT

    companion object {
        /** The side a live [OneHandedMode] renders on, or null when OFF. */
        fun of(mode: OneHandedMode): OneHandedSide? = when (mode) {
            OneHandedMode.LEFT -> LEFT
            OneHandedMode.RIGHT -> RIGHT
            OneHandedMode.OFF -> null
        }
    }
}

/**
 * One-handed geometry for a single screen orientation.
 *
 * [widthPercent] is the keyboard's share of the screen width while
 * one-handed is active (the rail and any leftover fill the rest).
 * [heightScale] shrinks the keys vertically as a percent of their normal
 * height, bringing the top rows into thumb reach. [side] is the edge the
 * keyboard docks to when one-handed is enabled in this orientation; the
 * in-keyboard rail's flip button updates it live.
 */
data class OneHandedProfile(
    val widthPercent: Int = 78,
    val heightScale: Int = 100,
    val side: OneHandedSide = OneHandedSide.RIGHT,
)

/**
 * Per-orientation one-handed tuning. Landscape defaults narrower because a
 * landscape keyboard is very wide, so 78% would barely help thumb reach.
 */
data class OneHandedSettings(
    val portrait: OneHandedProfile = OneHandedProfile(),
    val landscape: OneHandedProfile = OneHandedProfile(widthPercent = 55),
) {
    /** The profile that applies for the current orientation. */
    fun forLandscape(landscape: Boolean): OneHandedProfile =
        if (landscape) this.landscape else portrait
}

/** Where a width-reduced keyboard sits horizontally. */
enum class KeyboardAlignment { LEFT, CENTER, RIGHT }

/**
 * Whether the offline Whisper dictation engine can run in this build. False in
 * the lite flavor (no LiteRT runtime) — settings hide the engine option and the
 * IME never routes dictation through it.
 */
fun isWhisperEnabled(): Boolean =
    BuildConfig.ENABLE_WHISPER && com.wasimaster.wmkeyboard.core.voice.whisper.WhisperEngine.AVAILABLE

/**
 * Backend for the AI tool — cloud APIs (bring your own key), a self-hosted
 * server, or a model running entirely on this device (full builds only).
 */
enum class AiProvider(@StringRes val labelRes: Int) {
    ANTHROPIC(R.string.core_settings_ai_provider_anthropic_label),
    OPENAI(R.string.core_settings_ai_provider_openai_label),
    GEMINI(R.string.core_settings_ai_provider_gemini_label),
    OLLAMA(R.string.core_settings_ai_provider_ollama_label),
    LM_STUDIO(R.string.core_settings_ai_provider_lm_studio_label),
    ON_DEVICE(R.string.core_settings_ai_provider_on_device_label),

    // Appended, not inserted: the value is stored by name, and an entry that
    // changes position would still read back correctly, but the panel's model
    // picker walks `entries` and would silently reorder itself. Screens that
    // care about the order use [AiProvider.displayOrder] instead.
    XAI(R.string.core_settings_ai_provider_xai_label),
    DEEPSEEK(R.string.core_settings_ai_provider_deepseek_label),

    /**
     * Any other server that speaks the OpenAI chat-completions shape: the user
     * gives the address, the model and (if the service wants one) a key. This
     * is what covers OpenRouter, Groq, Together, Mistral and the rest without a
     * chip for each.
     */
    OPENAI_COMPATIBLE(R.string.core_settings_ai_provider_compatible_label),
    ;

    companion object {
        /**
         * The order the provider chips are drawn in: cloud services, then the
         * servers the user hosts, then the catch-all, then on-device. The
         * declaration order cannot do this job, because new entries can only be
         * appended and would land after ON_DEVICE.
         */
        val displayOrder: List<AiProvider> = listOf(
            ANTHROPIC, OPENAI, GEMINI, XAI, DEEPSEEK,
            OLLAMA, LM_STUDIO, OPENAI_COMPATIBLE, ON_DEVICE,
        )
    }
}

/**
 * Compute backend for on-device AI models. GPU is best-effort: the engine
 * falls back to CPU when GPU initialization fails on this device.
 */
enum class LocalLlmBackend(@StringRes val labelRes: Int) {
    CPU(R.string.core_settings_ai_backend_cpu_label),
    GPU(R.string.core_settings_ai_backend_gpu_label),
}

// The AI tool's actions used to be an enum here. They are a list the user owns
// now: see AiActionSpec and BuiltInAiActions in :core:tools, stored as JSON in
// AiSettings.customActions.

/** Error-correction level for generated QR codes (higher = more redundant). */
enum class QrEccLevel { L, M, Q, H }

/**
 * What reads the text in the scan text tool (full builds). ML Kit reads only
 * Latin script; Tesseract reads most scripts but needs each language's data
 * downloaded first.
 */
enum class OcrEngine {
    /** ML Kit for Latin-script languages, Tesseract for every other script. */
    AUTO,
    ML_KIT,
    TESSERACT,
}

/**
 * Where the translate tool gets its translations.
 *
 * Stored by name. [ONLINE] is first, what the tool has always done, and the
 * default everywhere but Play (see [TranslateSettings.engine]); the other two
 * only mean anything in a build that carries ML Kit.
 */
enum class TranslateEngine(@StringRes val labelRes: Int) {
    /** The online service: Google's endpoint, a Cloud key, or a LibreTranslate server. */
    ONLINE(R.string.core_settings_translate_engine_online_label),

    /** ML Kit on the device, and nothing else: text never leaves the phone. */
    ON_DEVICE(R.string.core_settings_translate_engine_on_device_label),

    /**
     * On the device whenever both languages are downloaded, online for the
     * rest: a language with no model, romanised text, a pair not fetched yet.
     */
    AUTO(R.string.core_settings_translate_engine_auto_label),
}

/**
 * The translate tool's own settings. A nested bag for the usual reason: one
 * slot on [KeyboardSettings] however many settings end up inside.
 */
data class TranslateSettings(
    /**
     * [TranslateEngine.ON_DEVICE] on Play, [TranslateEngine.ONLINE] elsewhere.
     * The Play build ships no Cloud Translation key, so its online engine is
     * Google's keyless public endpoint, which is not an API Google offers to
     * apps; and a shared key would be billed per character with no cap once
     * it is out of the APK. On the device, the first translation offers the
     * module and the model instead of fetching anything unasked. The other
     * builds keep Online: lite has no ML Kit, and a GitHub or F-Droid user
     * has always had it.
     */
    val engine: TranslateEngine =
        if (BuildConfig.ENABLE_PLAY_STORE) TranslateEngine.ON_DEVICE else TranslateEngine.ONLINE,
    /**
     * The panel's language menus list the languages whose model is on the
     * device first, while the engine is On device or Automatic. On Online
     * the models are not in play, so the menus keep their plain order.
     */
    val downloadedFirst: Boolean = true,
    /**
     * On the On device engine, the panel's language menus list only the
     * languages it can translate without a new download, the languages the
     * user types in and the current selection, then an "All languages" row
     * that shows the rest. Automatic is left alone: every language works
     * there, online if not on the device. Issue #324.
     */
    val onlyDownloaded: Boolean = true,
    /** DeepL, the user's own opt-in service (see [DeepLSettings]). Issue #331. */
    val deepl: DeepLSettings = DeepLSettings(),
)

/**
 * How DeepL Write should rewrite the text. DeepL's `prefer_` values: a
 * language whose Write model has no styles yet takes the text as plain
 * [DEFAULT] instead of refusing it. At most one of [writingStyle] and [tone]
 * is set, since DeepL takes only one per request.
 *
 * Stored by name, so rename nothing.
 */
enum class DeepLWriteStyle(
    @StringRes val labelRes: Int,
    val writingStyle: String? = null,
    val tone: String? = null,
) {
    DEFAULT(R.string.core_settings_deepl_style_default_label),
    SIMPLE(R.string.core_settings_deepl_style_simple_label, writingStyle = "prefer_simple"),
    BUSINESS(R.string.core_settings_deepl_style_business_label, writingStyle = "prefer_business"),
    ACADEMIC(R.string.core_settings_deepl_style_academic_label, writingStyle = "prefer_academic"),
    CASUAL(R.string.core_settings_deepl_style_casual_label, writingStyle = "prefer_casual"),
    FRIENDLY(R.string.core_settings_deepl_style_friendly_label, tone = "prefer_friendly"),
    CONFIDENT(R.string.core_settings_deepl_style_confident_label, tone = "prefer_confident"),
    DIPLOMATIC(R.string.core_settings_deepl_style_diplomatic_label, tone = "prefer_diplomatic"),
    ENTHUSIASTIC(R.string.core_settings_deepl_style_enthusiastic_label, tone = "prefer_enthusiastic"),
}

/**
 * DeepL, with a key the user brings (issue #331). Nothing here does anything
 * until [apiKey] or [endpoint] is filled in: the keyboard ships no DeepL key,
 * and with both blank the translate panel, the grammar panel and the
 * selection bar look and behave exactly as they would without this.
 */
data class DeepLSettings(
    /** DeepL API key. A Free key ends in `:fx` and is sent to DeepL's Free host. */
    val apiKey: String = "",
    /**
     * A server to send DeepL requests to instead of DeepL's own, for a proxy
     * the user runs. Blank means DeepL's host for the kind of key.
     */
    val endpoint: String = "",
    /** The translate tool asks DeepL first while a key is set. */
    val translate: Boolean = true,
    /**
     * DeepL Write shows in the grammar panel and the selection bar. Off until
     * asked for: Write needs an API Pro key, and a Free key is the common one.
     */
    val write: Boolean = false,
    val writeStyle: DeepLWriteStyle = DeepLWriteStyle.DEFAULT,
) {
    /** A key or a server to reach: the one thing that turns DeepL on at all. */
    val configured: Boolean get() = apiKey.isNotBlank() || endpoint.isNotBlank()

    val translateActive: Boolean get() = configured && translate

    val writeActive: Boolean get() = configured && write
}

/**
 * English dialect the offline grammar tool lints against. Ordinals are the
 * contract with the native Harper library — append only, never reorder.
 */
enum class GrammarDialect(@StringRes val labelRes: Int) {
    AMERICAN(R.string.core_settings_grammar_dialect_american_label),
    BRITISH(R.string.core_settings_grammar_dialect_british_label),
    CANADIAN(R.string.core_settings_grammar_dialect_canadian_label),
    AUSTRALIAN(R.string.core_settings_grammar_dialect_australian_label),
}

/**
 * The four buckets the grammar panel sorts issues into, each with its own
 * colour dot on the card. Harper's twenty fine-grained kinds map onto these
 * (see [GrammarLintKind.category]) so the header reads at a glance and so the
 * filter can be worked at either level.
 */
enum class GrammarCategory(@StringRes val labelRes: Int) {
    /** It is wrong: spelling, agreement, punctuation, a word mistaken for another. */
    CORRECTNESS(R.string.core_settings_grammar_category_correctness_label),
    /** It is right but hard work to read: wordy, repeated, the wrong word for the job. */
    CLARITY(R.string.core_settings_grammar_category_clarity_label),
    /** It could be better: a stylistic lift rather than a fix. */
    ENGAGEMENT(R.string.core_settings_grammar_category_engagement_label),
    /** It reads oddly for this audience: formatting, a regional form, a nonstandard one. */
    DELIVERY(R.string.core_settings_grammar_category_delivery_label),
}

/**
 * One kind of issue the offline grammar engine reports — the unit a filter
 * hides, and what [KeyboardSettings.grammarHiddenKinds] holds.
 *
 * These mirror Harper's `LintKind`. The names it puts on the wire are matched
 * letters-only and case-insensitively by [forKind], because Harper's own
 * spelling of them is not quite its variant names: `WordChoice` arrives as
 * "Word Choice". A kind this list does not know — a newer engine's — resolves
 * to null and is never filtered out, so an upgrade adds issues rather than
 * silently swallowing them.
 *
 * Enum names are persisted in DataStore, so rename nothing here.
 */
enum class GrammarLintKind(
    @StringRes val labelRes: Int,
    val category: GrammarCategory,
) {
    SPELLING(R.string.core_settings_grammar_kind_spelling_label, GrammarCategory.CORRECTNESS),
    TYPO(R.string.core_settings_grammar_kind_typo_label, GrammarCategory.CORRECTNESS),
    GRAMMAR(R.string.core_settings_grammar_kind_grammar_label, GrammarCategory.CORRECTNESS),
    AGREEMENT(R.string.core_settings_grammar_kind_agreement_label, GrammarCategory.CORRECTNESS),
    CAPITALIZATION(
        R.string.core_settings_grammar_kind_capitalization_label,
        GrammarCategory.CORRECTNESS,
    ),
    PUNCTUATION(R.string.core_settings_grammar_kind_punctuation_label, GrammarCategory.CORRECTNESS),
    BOUNDARY_ERROR(R.string.core_settings_grammar_kind_boundary_label, GrammarCategory.CORRECTNESS),
    MALAPROPISM(R.string.core_settings_grammar_kind_malapropism_label, GrammarCategory.CORRECTNESS),
    EGGCORN(R.string.core_settings_grammar_kind_eggcorn_label, GrammarCategory.CORRECTNESS),
    USAGE(R.string.core_settings_grammar_kind_usage_label, GrammarCategory.CORRECTNESS),
    READABILITY(R.string.core_settings_grammar_kind_readability_label, GrammarCategory.CLARITY),
    REDUNDANCY(R.string.core_settings_grammar_kind_redundancy_label, GrammarCategory.CLARITY),
    REPETITION(R.string.core_settings_grammar_kind_repetition_label, GrammarCategory.CLARITY),
    WORD_CHOICE(R.string.core_settings_grammar_kind_word_choice_label, GrammarCategory.CLARITY),
    ENHANCEMENT(R.string.core_settings_grammar_kind_enhancement_label, GrammarCategory.ENGAGEMENT),
    STYLE(R.string.core_settings_grammar_kind_style_label, GrammarCategory.ENGAGEMENT),
    MISCELLANEOUS(
        R.string.core_settings_grammar_kind_miscellaneous_label,
        GrammarCategory.ENGAGEMENT,
    ),
    FORMATTING(R.string.core_settings_grammar_kind_formatting_label, GrammarCategory.DELIVERY),
    REGIONALISM(R.string.core_settings_grammar_kind_regionalism_label, GrammarCategory.DELIVERY),
    NONSTANDARD(R.string.core_settings_grammar_kind_nonstandard_label, GrammarCategory.DELIVERY),
    ;

    companion object {
        private val byWireName = entries.associateBy { it.name.normalizedKind() }

        private fun String.normalizedKind(): String =
            lowercase().filter { it in 'a'..'z' }

        /** The kind [wireName] names, or null when the engine reports one we do not know. */
        fun forKind(wireName: String): GrammarLintKind? = byWireName[wireName.normalizedKind()]

        /**
         * Whether an issue reported as [wireName] should be shown given the
         * filter in [hidden]. Unknown kinds are shown: see the class KDoc.
         */
        fun isVisible(wireName: String, hidden: Set<GrammarLintKind>): Boolean =
            forKind(wireName)?.let { it !in hidden } ?: true

        /** Every kind in [category], in declaration order. */
        fun of(category: GrammarCategory): List<GrammarLintKind> =
            entries.filter { it.category == category }
    }
}

/** Content filter for the GIF and sticker tools (provider rating levels). */
enum class GifContentFilter { OFF, LOW, MEDIUM, HIGH }

/**
 * How the GIF/sticker panel presents multiple providers (KLIPY, GIPHY,
 * Google): a chip per source, or every source's results interleaved
 * evenly into one grid.
 */
enum class GifSourceMode { TABS, MIX }

/**
 * How stickers of your own are offered while you type their title, a keyword
 * or an emoji (#329).
 *
 * [TRAY] is a row of full-size stickers above the suggestion strip, arriving
 * with the match and leaving with it; the strip keeps its words. [STRIP] puts
 * a few small ones on the strip beside the words, with a button that opens the
 * tray for the rest. [CHIP] is one narrow chip saying how many there are,
 * which opens the tray when tapped.
 */
enum class StickerSuggestStyle { TRAY, STRIP, CHIP }

/**
 * What sending a suggested sticker does to the text that asked for it:
 * [DELETE] takes the title, keyword or emoji back out of the field, so the
 * sticker stands in for it; [KEEP] leaves the text where it is.
 */
enum class StickerTriggerAction { DELETE, KEEP }

/**
 * Key-press sound. [CLICK] and [STANDARD] come from the device's own sound
 * pack, so they match the stock keyboard's palette; [POP], [THOCK] and [CHIME]
 * are synthesised in-app. [CUSTOM] plays a file from
 * [com.wasimaster.wmkeyboard.core.feedback.SoundStore], named by
 * [KeySoundSettings.customId].
 */
enum class KeySoundStyle { CLICK, STANDARD, POP, THOCK, CHIME, CUSTOM, PACK }

/**
 * Key-press sound: whether it plays, which one, how loud, and which installed
 * file or pack the two selectable styles point at.
 *
 * Grouped because `KeyboardSettings` is at the JVM's 255-argument ceiling for
 * the `copy$default` Kotlin generates for it — see the note on
 * [CameraSettings]. The DataStore keys stay flat.
 */
data class KeySoundSettings(
    /** Whether a keypress makes a sound at all. */
    val enabled: Boolean = false,
    /** Which sound a keypress plays; see [KeySoundStyle]. */
    val style: KeySoundStyle = KeySoundStyle.CLICK,
    /** Sound-effect volume, 0..1 of the system media volume. */
    val volume: Float = 0.5f,
    /** [com.wasimaster.wmkeyboard.core.feedback.SoundStore] id, blank if none. */
    val customId: String = "",
    /**
     * [com.wasimaster.wmkeyboard.core.feedback.SoundPackStore] id, blank if
     * none — the pack [KeySoundStyle.PACK] plays.
     *
     * Kept beside [customId] rather than replacing it: the two styles remember
     * their own selection, so switching Custom -> Pack -> Custom does not lose
     * the sound the user had picked.
     */
    val packId: String = "",
    /**
     * Whether a key also sounds when it comes back up.
     *
     * Only a sound pack can have recorded that half — see
     * [com.wasimaster.wmkeyboard.core.feedback.KeySoundPhase] — so for every
     * other style this is inert. On by default: a pack that went to the trouble
     * of recording the switch returning should sound like the board it came
     * from, and the packs that did not are unaffected either way.
     */
    val playRelease: Boolean = true,
)

/**
 * Key-press haptic waveform.
 *
 * [SYSTEM_KEY] and [SYSTEM_TAP] delegate to the platform's own key haptic via
 * `View.performHapticFeedback` — the exact path stock keyboards use, so on
 * tuned OEMs (Samsung, Pixel) they inherit the vendor's crafted click and
 * follow the system haptic-intensity setting. They fall back to a hardware
 * click when no attached view is available.
 *
 * [SYSTEM_TAP] asks for `KEYBOARD_TAP` and is the best of them where the
 * vendor tuned one: OEMs give keyboards a separate waveform, and it is the one
 * their own keyboard plays. Measured on a Galaxy S25 Ultra (One UI 8),
 * `KEYBOARD_TAP` and `VIRTUAL_KEY` resolve to *different* Samsung effects —
 * 50025 vs 50038 — at near-identical durations (122 ms vs 132 ms). Samsung's
 * Honeyboard and Ridmik both play 50025; 50038 is the generic button press,
 * and it reads as duller. `KEYBOARD_TAP` also lands the vibration under
 * `VibrationAttributes.USAGE_IME` rather than `USAGE_TOUCH`, which is the
 * bucket an OEM's keyboard-vibration setting governs.
 *
 * [SYSTEM_KEY] asks for `VIRTUAL_KEY` — for devices whose vendor never tuned a
 * keyboard-specific effect, where the two are the same waveform.
 *
 * It is [SYSTEM_TAP] that [HapticSettings.style] declares, but almost
 * nobody types on it: onboarding writes
 * `HapticPlayer.bestSupportedStyle(context)` over the declared value, and that
 * function only ever returns [SYSTEM_KEY] or [HEAVY_CLICK]. In practice
 * [SYSTEM_TAP] ships only to someone who skipped onboarding or picked it from
 * the styles list. The declared value is what a reset lands on, so it stays as
 * it is; the shipped default is whatever `bestSupportedStyle` said on that
 * device.
 *
 * The rest drive the vibrator directly: [CUSTOM] with the duration/amplitude
 * sliders; [CLICK]/[HEAVY_CLICK] with the device's predefined effects
 * (Android 10+); [SHARP] with the click primitive (Android 11+).
 */
// Declared best-to-worst: the two recommended platform styles first, then the
// hardware-tuned effects, then the manual Custom fallback last. UIs iterate
// `entries`, so this order drives their display. Persistence keys off `.name`,
// so reordering is storage-safe. [labelRes] is the short chip caption shared by
// every picker; resolve it where it is drawn.
enum class HapticStyle(@StringRes val labelRes: Int) {
    SYSTEM_TAP(R.string.core_settings_haptic_style_system_tap_label),
    SYSTEM_KEY(R.string.core_settings_haptic_style_system_key_label),
    CLICK(R.string.core_settings_haptic_style_click_label),
    HEAVY_CLICK(R.string.core_settings_haptic_style_heavy_label),
    SHARP(R.string.core_settings_haptic_style_sharp_label),
    CUSTOM(CommonR.string.common_custom),
}

/**
 * The vibration a keypress makes: whether it happens, its waveform, and the
 * two dials the manual waveforms use.
 *
 * Which *events* vibrate (as opposed to how) is [FeedbackSettings]; this is the
 * key-press one every other gate defers to.
 *
 * Grouped because `KeyboardSettings` is at the JVM's 255-argument ceiling for
 * the `copy$default` Kotlin generates for it — see the note on
 * [CameraSettings]. The DataStore keys stay flat.
 */
data class HapticSettings(
    /** Whether a keypress vibrates at all. */
    val enabled: Boolean = true,
    /** Vibration length for [HapticStyle.CUSTOM], in milliseconds. */
    val strengthMs: Int = 15,
    /** Vibration amplitude, 1..255, for [HapticStyle.CUSTOM] and [HapticStyle.SHARP]. */
    val amplitude: Int = 255,
    /**
     * See [HapticStyle]. Onboarding overwrites this with
     * `HapticPlayer.bestSupportedStyle(context)`, so on a phone that has been
     * through the wizard the style is [HapticStyle.SYSTEM_KEY] or
     * [HapticStyle.HEAVY_CLICK] and never this one. This value is what a reset
     * goes back to.
     */
    val style: HapticStyle = HapticStyle.SYSTEM_TAP,
    /** Vibrate when a long press fires. */
    val onLongPress: Boolean = true,
    /** Vibrate again when the finger lifts off a long press. */
    val onLongPressRelease: Boolean = false,
)

/**
 * What a horizontal swipe on the spacebar does. "Short" swipes start
 * moving right away; "long" swipes hold the spacebar past the long-press
 * delay first, then drag — distance is deliberately not the discriminator,
 * a fast flick travels further than a careful drag.
 */
enum class SpaceSwipeAction { NONE, LANGUAGE, CURSOR, NUMPAD }

/**
 * What the resting spacebar label shows. [LANGUAGE] the current language name,
 * [LAYOUT] the current layout name, [BOTH] "Language (Layout)". Regardless of
 * mode, when the active language has more than one enabled layout the layout
 * name is appended anyway, so those layouts stay distinguishable.
 */
enum class SpacebarDisplay { LANGUAGE, LAYOUT, BOTH }

/**
 * The shape of the language picker: the tappable chooser a spacebar hold or a
 * 🌐 long press opens once the ring is too long for the inline preview.
 *
 *  - [LIST] — a vertical list; a spacebar hold-drag walks it up and down.
 *  - [CAROUSEL] — a horizontal strip centred on the current layout; the
 *    hold-drag keeps the swipe's own direction (issue #150). Chips scroll
 *    and are tappable, and the strip re-centres on whatever is highlighted,
 *    so a long ring never turns a sideways gesture into a vertical one.
 */
enum class LanguagePickerStyle { LIST, CAROUSEL }

/**
 * What the corner hints on a transliterating layout (Avro) show, with the
 * roman `k` pressed after another `k` as the example:
 *
 *  - [OFF] — nothing; the keys keep their ordinary long-press hints.
 *  - [ADDED] — only what the key writes: ্ক. Exact, and short, but a bare
 *    hasant or kar is a mark with nothing to sit on.
 *  - [CLUSTER] — the whole conjunct the key lands in: ক্ক. It repeats the
 *    consonant already typed, and in exchange every hint is a shape that
 *    really appears in the word.
 */
enum class TransliterationHintMode { OFF, ADDED, CLUSTER }

/**
 * What a swipe across the letter keys does. TYPE_WORDS is the classic glide
 * decoder; HANDWRITE turns the same swipe into a handwriting stroke fed to the
 * ML Kit recognizer (full builds only — needs a downloaded handwriting model).
 */
enum class LetterSwipeAction { TYPE_WORDS, HANDWRITE }

/**
 * Which key a glide reads as an apostrophe, so a contraction can be *drawn*:
 * `i → t → ' → s` spells "it's" rather than "its".
 *
 * The apostrophe is the one character a glide cannot reach on a normal layout,
 * which is why "its" and "it's", "were" and "we're", "developers" and
 * "developer's" all decode the same today and the spelling is decided by
 * frequency. [GestureSettings.apostropheKey] hands that decision back to the
 * finger.
 *
 * Exactly one key, never several. Two keys standing for the apostrophe at once
 * measurably degrades every *other* word on the board, because both keys stop
 * being the punctuation they are drawn as as far as the decoder is concerned.
 * [OFF] is the default and changes nothing.
 *
 * [SPACE] is the one with a side effect: the spacebar already means "this word
 * ends here" mid-stroke ([GestureSettings.spaceGlideMultiWord]), and one
 * crossing cannot be read as both. Picking it suspends the multi-word split for
 * as long as it is chosen.
 */
enum class GlideApostropheKey { OFF, COMMA, PERIOD, SPACE, APOSTROPHE }

/**
 * What a glide drawn through the shift key asks for (#163).
 *
 * [WORD] is the ladder tapping shift walks up: one crossing capitalizes the
 * word, two shout it. [LETTER] reads each crossing as a capital for the
 * letter the stroke just left, so `HeLLo` and `LeanType` can be drawn, and a
 * stroke that ends on the shift key shouts the word — one trip rather than
 * two. See [GestureSettings.shiftGlideMode].
 */
enum class ShiftGlideMode { WORD, LETTER }

/** The character each [GlideApostropheKey] borrows, or null for [GlideApostropheKey.OFF]/SPACE. */
val GlideApostropheKey.sourceChar: Char?
    get() = when (this) {
        GlideApostropheKey.COMMA -> ','
        GlideApostropheKey.PERIOD -> '.'
        GlideApostropheKey.APOSTROPHE -> '\''
        GlideApostropheKey.OFF, GlideApostropheKey.SPACE -> null
    }

/**
 * How many of a dictionary's commonest words glide typing decodes against.
 *
 * A swipe names a rough path through the keys and nothing else, so the language
 * model does most of the deciding — and on a downloaded corpus of a million-odd
 * words most of that vocabulary is a tail nobody writes, each entry needing
 * only a marginally better-fitting shape to beat the word that was meant. The
 * cap bounds what a *stroke* may answer with. It does not touch the dictionary:
 * completion, autocorrect and the suggestion strip keep every word, because a
 * typed prefix is evidence enough to pull a rare word out of a million and a
 * swipe is not.
 *
 * Measured on the real 1.6M-word English list, drawing 1600 synthetic strokes
 * for words from the 17k bundled list across the four noise levels:
 *
 * | vocabulary            | top1  | top3  | ms/stroke |
 * |-----------------------|-------|-------|-----------|
 * | 17k bundled list      | .9438 | .9881 |      7.44 |
 * | 1.6M list, no cap     | .7813 | .8269 |     17.66 |
 * | 1.6M list, [LARGE]    | .8575 | .9063 |     12.17 |
 * | 1.6M list, [MEDIUM]   | .8906 | .9413 |      7.48 |
 * | 1.6M list, [SMALL]    | .9250 | .9756 |      5.82 |
 *
 * Downloading the whole list costs 16 points of top-1 accuracy, which is the
 * complaint in #28 stated as a number, and a cap gets most of it back while
 * making the decode up to three times faster. [LARGE] is the default: it is
 * provably inert on every list of 300k words or fewer — the bundled lists and
 * every download but [DictionaryCatalog.DictionarySize.ALL] — so it only ever
 * touches the deep tail, which on that corpus is words appearing six times or
 * fewer in 28M tokens.
 *
 * The measurement is honest about one thing it cannot show: the strokes are
 * drawn for words in the seed list, so a cap can never lose one of them. What
 * it establishes is that the tail costs common words dearly, not what capping
 * costs someone who glides genuinely rare words. That is what the setting is
 * for, and why the default is the widest cap rather than the best-scoring one.
 */
enum class GlideVocabulary(@StringRes val labelRes: Int, val rank: Int) {
    ALL(PredictionR.string.core_pred_wordlist_size_all_label, 0),
    LARGE(PredictionR.string.core_pred_wordlist_size_large_label, 300_000),
    MEDIUM(PredictionR.string.core_pred_wordlist_size_medium_label, 150_000),
    SMALL(PredictionR.string.core_pred_wordlist_size_small_label, 50_000),
}

/**
 * How close a swipe's two best readings have to be before the ambiguity picker
 * asks ([GestureSettings.ambiguityPicker]).
 *
 * The measure is the gap between the leader and the runner-up in nats, the
 * decoder's own log units, so each tier reads as a likelihood ratio: the picker
 * asks when the leader is less than [margin] ahead. [CLOSE_CALLS] is
 * [SuggestionEngine.AMBIGUOUS_MARGIN], which is what every stroke was judged by
 * before this was a setting. [EVERY_PAUSE] has no threshold at all: any stroke
 * with two readings asks the moment the finger holds still.
 */
enum class GlidePickerSensitivity(@StringRes val labelRes: Int, val margin: Double) {
    /** Only near-ties: the two words within about 1.5× of each other. */
    NEAR_TIES(R.string.core_settings_glide_picker_near_ties_label, 0.4),

    /** Within about 3×. */
    CLOSE_CALLS(R.string.core_settings_glide_picker_close_calls_label, SuggestionEngine.AMBIGUOUS_MARGIN),

    /** Within about 12×. */
    ANY_DOUBT(R.string.core_settings_glide_picker_any_doubt_label, 2.5),

    /** Every pause asks, whatever the decoder thinks. */
    EVERY_PAUSE(R.string.core_settings_glide_picker_every_pause_label, Double.POSITIVE_INFINITY),
}

/**
 * Whose words a swipe may answer with — the other half of the question
 * [GlideVocabulary] answers on the frequency axis.
 *
 * A swipe is a weak signal, so what it competes against decides its accuracy.
 * [GlideVocabulary] takes the rare tail out of the search; this can take the
 * *dictionary* out of it, leaving only the words this user has actually
 * written. On a synthetic user whose vocabulary is a 2,000-word sample of the
 * shipped list and whose personal lexicon is that vocabulary, over typical and
 * sloppy strokes:
 *
 * | policy         | on words they write | on words they have not |
 * |----------------|---------------------|------------------------|
 * | [NORMAL]       | .9188               | .9125                  |
 * | [LEARNED_ONLY] | .9750               | .0000                  |
 *
 * Five and a half points on everything they write, and nothing at all on
 * anything they do not. The trade only pays while fewer than about one swipe in
 * twenty is for a word the keyboard has not learned
 * (`GlideSandboxLadder.ONLY_AT_NEW_WORD_RATE`), which is a fact about the
 * person rather than about the decoder — hence a setting, [AUTOMATIC] to
 * measure it, and [NORMAL] as the default.
 *
 * A swipe that lands the wrong word is never a dead end under any of these:
 * backspacing it re-decodes the same stroke against every word there is, which
 * is the manual search these policies are designed around.
 */
enum class GlideSandbox(@StringRes val labelRes: Int) {
    /** Every source at its own weight — what the decoder has always done. */
    NORMAL(R.string.core_settings_glide_sandbox_normal_label),

    /**
     * Both decodes run and the learned words' answer is taken when it fits at
     * least as well, while the keyboard counts how often they could have
     * answered alone.
     *
     * Worth no accuracy on its own and not meant to be — it is how [AUTOMATIC]
     * finds out whether [LEARNED_ONLY] would suit this user. Pickable directly
     * for anyone who wants the measurement without the ladder.
     */
    PREFER_LEARNED(R.string.core_settings_glide_sandbox_prefer_label),

    /** Only the learned words. The dictionary never runs. */
    LEARNED_ONLY(R.string.core_settings_glide_sandbox_only_label),

    /**
     * Climbs from [NORMAL] to [PREFER_LEARNED] once the personal lexicon is
     * large enough to be a vocabulary, and from there to [LEARNED_ONLY] once
     * the measurement says it is answering nearly every swipe. Each step is
     * offered rather than taken: a policy that changes what a swipe types
     * should not do it behind the user's back.
     */
    AUTOMATIC(R.string.core_settings_glide_sandbox_automatic_label),
}

/**
 * How hard the word a stroke is being read as resists being replaced while the
 * finger is still down.
 *
 * A glide decodes on every touch move, and early in a stroke the leader is
 * genuinely unstable: two letters in, half the dictionary fits. Shown raw, that
 * is a strip — and now a set of words drawn on the keys — churning several
 * times a second under a finger that is still moving. The HCI work on mid-swipe
 * prediction is blunt that this is its main cost: people report having to look
 * back and forth between the finger and the preview, which breaks the motor
 * flow the preview was supposed to help.
 *
 * The gate is the standard answer: a challenger must beat the word on screen by
 * [margin] nats, measured *within one reading* so the comparison is like for
 * like, and the word on screen gets [holdMs] to itself before any challenger is
 * heard at all. A word that drops out of the candidates entirely is replaced
 * immediately whatever the tier says — it is not a reading of this stroke any
 * more.
 *
 * Display and commit stay in agreement: a lift takes the word that is on
 * screen, provided the final decode still ranks it within [margin] of its own
 * leader. Seeing one word and getting another is the failure this whole setting
 * exists to avoid, so the gate must not introduce it.
 */
enum class GlidePreviewSteadiness(
    @StringRes val labelRes: Int,
    /** Nats a challenger must beat the shown word by. */
    val margin: Double,
    /** How long the shown word is safe from any challenger, in ms. */
    val holdMs: Int,
) {
    /** Every reading goes straight to the screen, as it always did. */
    OFF(R.string.core_settings_glide_steadiness_off_label, 0.0, 0),

    /** Enough to absorb the churn of a near-tie without holding a beaten word. */
    LIGHT(R.string.core_settings_glide_steadiness_light_label, 0.6, 90),

    /** A word stays put unless it is clearly beaten. */
    STEADY(R.string.core_settings_glide_steadiness_steady_label, 1.5, 160),

    /** For a hand that finds any movement distracting. */
    VERY_STEADY(R.string.core_settings_glide_steadiness_very_steady_label, 3.0, 260),
}

/**
 * Whether a glide may show a word the stroke has not finished spelling, and how
 * sure it has to be first.
 *
 * A swipe decoder reads a word by putting its first letter on the first sample
 * and its last on the last, which is exact and is why "dictionary" cannot
 * appear until the finger has reached the `y` — while a tap typist sees it four
 * letters in. Early prediction lifts the end anchor for a *prefix*: the stroke
 * so far explains the start of the word, and the rest is the language model's
 * guess about where the finger is going.
 *
 * The guess is only worth showing when it is well clear of the best ordinary
 * reading, which is what [margin] is. The research on this is unanimous that
 * the failure mode is not a wrong guess but a *flickering* one — a preview that
 * offers a long word, withdraws it, and offers another breaks the motor flow it
 * was meant to help — so the tiers are confidence tiers, and the steadiness
 * gate ([GlidePreviewSteadiness]) applies on top of whatever this admits.
 *
 * Lifting takes the word on screen, guess included. That is the feature: a long
 * word costs the few letters it took to make the keyboard sure, rather than all
 * of them.
 *
 * **How well it works, measured.** Full strokes for words of six letters or
 * more, cut short and decoded as if the finger were still moving; the figure is
 * how often the guess on screen is the word that was meant:
 *
 * | vocabulary          | 40% drawn | 60% drawn | 80% drawn |
 * |---------------------|-----------|-----------|-----------|
 * | the 17k dictionary  | .069      | .234      | .460      |
 * | a 2k personal one   | .183      | .345      | .640      |
 *
 * Two things follow, and both are why this ships off. It is wrong more often
 * than right until a stroke is most of the way through the word, so it is a
 * feature for people who want it rather than one to give everybody. And it is
 * roughly twice as accurate answering out of one person's vocabulary as out of
 * a dictionary — so it belongs with [GlideSandbox.LEARNED_ONLY], which is what
 * the proposal it comes from guessed without being able to measure it.
 *
 * The cost of a wrong guess is smaller than that table makes it look: it is a
 * word *shown*, not a word typed, and the user rejects it by carrying on
 * drawing. [GlidePreviewSteadiness] governs how much it may churn while they
 * do. But it is not nothing, which is what [margin] is for.
 */
enum class GlideLookAhead(
    @StringRes val labelRes: Int,
    /** Nats a guess must beat the best ordinary reading by; 0 disables. */
    val margin: Double,
) {
    /** No guessing: a stroke answers with what it has spelled. */
    OFF(R.string.core_settings_glide_lookahead_off_label, 0.0),

    /** Only when the guess is far ahead of anything the stroke actually spells. */
    CONFIDENT(R.string.core_settings_glide_lookahead_confident_label, 4.0),

    /** Whenever the guess is ahead at all. */
    EAGER(R.string.core_settings_glide_lookahead_eager_label, 1.0),
}

/**
 * Which word a stroke being drawn is given the suggestion strip's own colour,
 * instead of only the bold the leading suggestion always gets.
 *
 * The strip has coloured the word autocorrect will put in for a space since
 * issue #90, and the colour there means one thing: this is not the top guess,
 * it is what the next commit will really type. A glide can make the same
 * promise, and while [GlideLookAhead] is on it is worth making, because the
 * word on screen may carry letters the finger has not drawn and the user has
 * no other way to tell.
 *
 * The colour itself is the strip's, from
 * [SuggestionStripSettings.primaryColor]. Nothing draws differently until that
 * colour is set, exactly as with autocorrect.
 */
enum class GlideCommitColor(@StringRes val labelRes: Int) {
    /** Bold and nothing else, whatever the stroke is being read as. */
    OFF(R.string.core_settings_glide_commit_color_off_label),

    /**
     * Only a guess the decoder is sure of: one beating the best word the
     * stroke actually spells by [GlideLookAhead.CONFIDENT]'s margin. On
     * [GlideLookAhead.EAGER] this is a second, stricter level, so bold says
     * "ahead of what you drew" and the colour says "sure of it".
     */
    CONFIDENT(R.string.core_settings_glide_commit_color_confident_label),

    /** Any word carrying letters the stroke has not drawn. */
    GUESS(R.string.core_settings_glide_commit_color_guess_label),

    /** Every stroke, guess or not: the colour means "lift now and get this". */
    ALWAYS(R.string.core_settings_glide_commit_color_always_label),
}

/** Which surfaces [GlideCommitColor] reaches. */
enum class GlideCommitColorScope(@StringRes val labelRes: Int) {
    /** The suggestion strip alone, where the autocorrect colour already lives. */
    STRIP(R.string.core_settings_glide_commit_scope_strip_label),

    /** The strip and the word pill riding above the fingertip. */
    STRIP_AND_PILL(R.string.core_settings_glide_commit_scope_pill_label),

    /** Those two and the floating words drawn on the keys. */
    EVERYWHERE(R.string.core_settings_glide_commit_scope_keys_label),
}

/** What the history tab of the emoji panel shows. */
enum class EmojiTabMode { RECENTS, MOST_USED }

/**
 * The dedicated emoji row (Gboard style): [ALWAYS] keeps it as its own row
 * above the keys, [BUTTON] tucks it behind a toggle on the toolbar strip,
 * [OFF] hides it entirely.
 */
enum class EmojiBarMode { OFF, BUTTON, ALWAYS }

/** Which emojis the dedicated emoji row shows (favourites always lead). */
enum class EmojiBarContent { MOST_USED, RECENTS, FAVOURITES }

/**
 * Which font renders emojis on the keyboard itself (panel, emoji row,
 * suggestions). [SYSTEM] uses the device's emoji font (Samsung's own pack
 * on Samsung phones), [NOTO] downloads Google's Noto Color Emoji — the
 * stock-Android look — via the Google Fonts provider, [CUSTOM] uses an
 * emoji font file the user imported. Text committed to apps is plain
 * Unicode either way; the receiving app draws it with its own font.
 */
enum class EmojiFontChoice { SYSTEM, NOTO, CUSTOM, INSTALLED }

/**
 * Which emoji face from the font library [EmojiFontChoice.INSTALLED] draws with.
 *
 * Its own class for the reason [KeySoundSettings] is: `KeyboardSettings` sits at
 * the JVM's 255-argument ceiling for the `copy$default` Kotlin generates, so a
 * new setting joins a group rather than the flat list. The DataStore key is flat
 * either way.
 */
data class EmojiFontSettings(
    /** [com.wasimaster.wmkeyboard.core.fonts.FontStore] id, blank if none. */
    val installedId: String = "",
)

/**
 * What tapping an emoji suggestion does to the word being typed:
 * [REPLACE] swaps the word for the emoji (Gboard style), [APPEND] keeps
 * the word and adds the emoji after it ("birthday 🎂").
 */
enum class EmojiInsertMode { REPLACE, APPEND }

/**
 * Default Fitzpatrick skin tone applied to toned emoji in the suggestion
 * strip and emoji search. [NONE] leaves the neutral yellow base; the five
 * others map to 🏻..🏿, i.e. tone indices 1..5 in [EmojiVariantIndex].
 */
enum class EmojiSkinTone(val toneIndex: Int) {
    NONE(0), LIGHT(1), MEDIUM_LIGHT(2), MEDIUM(3), MEDIUM_DARK(4), DARK(5),
}

/**
 * How the keyboard exposes itself to TalkBack. [OFF] leaves the keys as raw
 * touch targets (what a screen-reader user gets today: nothing readable).
 * [LABELS] adds spoken labels but keeps direct typing, which suits switch
 * access and low-vision users who still touch-type. [EXPLORE] is the
 * conventional IME behaviour under touch exploration — drag to hear a key,
 * lift to type it — and is what TalkBack users expect.
 *
 * [PASSTHROUGH] keeps the keyboard's own touch handling under a screen reader
 * — the spacebar cursor slide, the backspace word swipe, glide typing and
 * handwriting all keep working, and a key still announces on press and types
 * on release. It needs the app's pass-through accessibility service enabled
 * (see `core.accessibility.TouchPassthroughService`), because carving the
 * keyboard out of touch exploration is something only an accessibility service
 * may ask for; without it the mode falls back to [EXPLORE].
 */
enum class ScreenReaderMode { OFF, LABELS, EXPLORE, PASSTHROUGH }

/**
 * Legibility and motor-access settings: what the keys look like to someone who
 * needs more contrast than the theme gives, and how forgiving a tap is.
 *
 * `reduceMotion` deliberately stays a top-level field — it is read from Compose
 * all over both apps, including off `KbTheme`, and a nested read there buys
 * nothing.
 *
 * Grouped because `KeyboardSettings` is at the JVM's 255-argument ceiling for
 * the `copy$default` Kotlin generates for it — see the note on
 * [CameraSettings]. The DataStore keys stay flat.
 */
data class AccessibilitySettings(
    /** Daltonization / grayscale applied over the resolved theme palette. */
    val colorVision: ColorVisionFilter = ColorVisionFilter.NONE,
    /** Force key text to maximum contrast and separate the board from the keys. */
    val highContrast: Boolean = false,
    /** Draw an outline on every key, so key edges don't rely on fill contrast. */
    val keyOutlines: Boolean = false,
    /** Render key labels bold. */
    val boldLabels: Boolean = false,
    /**
     * See [ScreenReaderMode]. [ScreenReaderMode.EXPLORE] by default: it is what
     * every other IME does under touch exploration, so a TalkBack user meets
     * the gesture they already know rather than one this keyboard invented.
     */
    val screenReader: ScreenReaderMode = ScreenReaderMode.EXPLORE,
    /**
     * Ignore a repeat press of the same key within this many milliseconds
     * (0 = off). The tremor/spasticity counterpart to a long-press delay:
     * it drops the unintended second contact of a bouncing tap.
     */
    val keyDebounceMs: Int = 0,
)

/**
 * How the top toolbar behaves and lays out. Grouped into their own class
 * rather than sitting flat on [KeyboardSettings] because that class's primary
 * constructor is at the JVM's 255-argument ceiling — new toolbar settings land
 * here, and existing flat ones are migrated in as room is needed. Each field
 * still persists under its own DataStore key via the matching setter.
 */
data class ToolbarBehavior(
    /**
     * Master switch for the whole top strip (suggestions + toolbar). Off
     * removes it entirely, reclaiming its height for the keys. Guarded by a
     * warning in Settings because it hides suggestions and every pinned tool.
     */
    val enabled: Boolean = true,
    /**
     * Swipe down anywhere on the top strip to dismiss the keyboard, the way a
     * downward flick on the keys does on some keyboards. Off by default so the
     * gesture never surprises anyone reordering or scrolling the bar.
     */
    val swipeDownHide: Boolean = false,
    /**
     * Whether a press-and-hold that travels picks a pinned tool up and drops it
     * elsewhere on the bar. On by default, which is how the toolbar has always
     * been rearranged. Off, a hold that drifts stays a hold — the bound action
     * or the settings page on the lift — and the toolbox is where the bar gets
     * rearranged instead (#136). The toolbox's own drag is untouched: it is the
     * only way to pin a tool.
     */
    val dragToRearrange: Boolean = true,
    /**
     * With a physical keyboard attached, drop the on-screen keys and keep only
     * the toolbar strip, so the tools stay one tap away while typing on the
     * hardware keyboard. Off by default (the platform's usual behaviour stands).
     */
    val onlyWithHardwareKeyboard: Boolean = false,
    /**
     * Mirror the pinned tool order left-to-right when the active layout's
     * script runs right-to-left (Arabic, Hebrew …), so the bar reads with the
     * text. The toolbox grid is unaffected either way.
     *
     * Off by default. The bar holds buttons, not text, so nothing about it has
     * a reading order the script gets to dictate — and a bilingual user pays
     * for the mirroring on every single language switch, with every tool
     * landing where a different one was a moment ago. Someone who wants the bar
     * to follow the text can still say so; the reverse (finding the switch
     * after your muscle memory has already been broken a hundred times) is the
     * worse default.
     */
    val reverseForRtl: Boolean = false,
    /** Pinned tools split the bar width evenly instead of packing to the left. */
    val greedy: Boolean = true,
    /**
     * Let the pinned tools scroll horizontally instead of packing into the
     * bar width — for people who pin more tools than fit at a tappable size.
     * Forces the packed (non-greedy) layout while on.
     */
    val scrollable: Boolean = false,
    /**
     * On the device lock screen, hide the whole top strip (suggestions +
     * toolbar) and block the clipboard panel, so copied text — one-time codes,
     * passwords — and every pinned tool stay off a screen anyone can wake. Off
     * by default; locked or not, the keyboard looks the same.
     */
    val hideWhenLocked: Boolean = false,
    /**
     * Width of each pinned tool's background on the bar. 38 is the classic
     * circle; wider stretches it into a pill (pairs best with a low corner
     * radius and the scrollable bar for a Gboard-style strip). The background
     * still needs a corner radius above zero to be visible at all.
     */
    val toolWidthDp: Int = 38,
    /**
     * Space above the toolbar's content, in dp, added to the strip's height.
     * 4 by default (#208): with none, the tool pills sat almost against the
     * keyboard's top edge, closer than any two key rows sit to each other.
     */
    val paddingTopDp: Int = 4,
    /** Space between the toolbar's content and the keys, in dp, added to the strip's height. */
    val paddingBottomDp: Int = 0,
    /**
     * Which built-in themes the keyboard's Themes tool offers, by id — a
     * quick-switch shortlist for changing looks mid-typing, while the full
     * gallery stays in Settings. Custom and downloaded themes always show.
     * Null (never touched) falls back to [DefaultThemesPanelBuiltIns]; the
     * theme gallery in Settings edits it per card.
     */
    val themesPanelBuiltIns: Set<String>? = null,
    /**
     * Where the pinned tools live: sharing the suggestion strip's row (the
     * default), or on a row of their own above it.
     */
    val placement: ToolbarPlacement = ToolbarPlacement.STRIP,
    /**
     * Whether the suggestion strip keeps its row while the tools have one of
     * their own and it is always open ([ToolbarPlacement.ALWAYS_ROW]). On by
     * default. Off is for someone who has turned suggestions off and was left
     * with an empty band over the tools (#302): the tools row stays, and the
     * strip's height goes back to the keys. Read only under ALWAYS_ROW, since
     * the other own-row placement opens its row from the strip's chevron. See
     * [stripHidden].
     */
    val showStrip: Boolean = true,
    /**
     * What a press and hold on a pinned tool does, per tool, as tool name →
     * action token (see [ToolHoldActions]).
     *
     * Empty by default, which is the behaviour the toolbar has always had: a
     * hold opens that tool's settings page. Naming a tool here spends the hold
     * on a second action instead — hold Undo to redo, hold a cursor key to jump
     * to the end of the line, hold the text-editing tool to open the clipboard.
     * The settings page a tool gives up is still a hold away in the toolbox and
     * still on the Tools screen, exactly as it is for the cursor tools that
     * repeat on hold.
     *
     * Only the toolbar reads this. The toolbox is where every tool's settings
     * page stays reachable by hold, so remapping it there would leave some
     * pages with no way in.
     *
     * A tool may also be bound to [ToolHoldAction.None] (#136): the hold then
     * does nothing at all, for someone whose hold keeps landing on a page they
     * never asked for.
     */
    val holdActions: Map<ToolbarTool, ToolHoldAction> = emptyMap(),
)

/**
 * What a press and hold on a pinned tool does once the user has changed it
 * from the default. Absent from [ToolbarBehavior.holdActions] means the
 * default: the tool's settings page opens.
 */
sealed interface ToolHoldAction {
    /** The hold does nothing: no page, no action, only the buzz (#136). */
    data object None : ToolHoldAction

    /** The hold runs [tool]'s tap. */
    data class Run(val tool: ToolbarTool) : ToolHoldAction
}

/**
 * Where the pinned tools are drawn.
 *
 * [STRIP] is the original arrangement and the default: tools and suggestions
 * share one row, the tools resting there when there is nothing to suggest and a
 * chevron flipping between the two. It costs no height, and it is the reason the
 * toolbar is not always in reach.
 *
 * The other two give the tools a row of their own above the suggestions, which
 * is what a keyboard has to do for the tools to be there whatever the strip is
 * showing. [ON_DEMAND_ROW] opens that row from the chevron and closes it again
 * (FUTO's arrangement); [ALWAYS_ROW] keeps it open (LeanType's). Both cost one
 * strip's worth of keyboard height while the row is up, which is the trade.
 */
enum class ToolbarPlacement { STRIP, ON_DEMAND_ROW, ALWAYS_ROW }

/**
 * The bottom padding the keyboard uses while [KeyboardSettings.bottomPaddingDp]
 * is unset.
 *
 * From Android 15 the IME window runs edge to edge and the keys stand on
 * `navigationBarsPadding`. Above a gesture bar that inset is only the thin
 * handle strip, and a bottom row that close to the edge starts the home
 * gesture by mistake, so the default adds 32dp. Above three-button navigation
 * the inset is the whole button bar, which is tappable and starts nothing, and
 * the same 32dp left an empty band between the space bar and the buttons
 * (#343). There, and anywhere below 15 where the window stops above the bar,
 * it is 8dp.
 *
 * @param gestureBar true when a gesture handle, not a row of buttons, is what
 *   sits along the bottom edge.
 */
fun autoBottomPaddingDp(gestureBar: Boolean): Int =
    if (gestureBar && Build.VERSION.SDK_INT >= 35) 32 else 8

/** [KeyboardSettings.bottomPaddingDp], or [autoBottomPaddingDp] while it is unset. */
fun KeyboardSettings.bottomPaddingOr(gestureBar: Boolean): Int =
    bottomPaddingDp ?: autoBottomPaddingDp(gestureBar)

/** True while the tools have a row of their own rather than sharing the strip. */
val ToolbarPlacement.isOwnRow: Boolean get() = this != ToolbarPlacement.STRIP

/**
 * True while the suggestion strip has given up its row (#302): the tools are
 * on an always-open row of their own and [ToolbarBehavior.showStrip] is off.
 */
val ToolbarBehavior.stripHidden: Boolean
    get() = enabled && placement == ToolbarPlacement.ALWAYS_ROW && !showStrip

/**
 * The `tool=action` CSV behind [ToolbarBehavior.holdActions].
 *
 * A tool this build does not have is dropped on both sides rather than
 * corrupting the map — the same rule `IconOverrides` follows — so a map written
 * by a newer build costs that one entry and no more.
 */
object ToolHoldActions {

    /**
     * The action token for [ToolHoldAction.None]. Not a tool name, and never
     * can be: [ToolbarTool] has no such constant, so the two vocabularies stay
     * apart in the stored string.
     */
    const val NONE_TOKEN = "NONE"

    fun decode(csv: String?): Map<ToolbarTool, ToolHoldAction> =
        csv?.split(',')?.mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val tool = toolOrNull(entry.substring(0, separator)) ?: return@mapNotNull null
            val token = entry.substring(separator + 1)
            if (token == NONE_TOKEN) return@mapNotNull tool to ToolHoldAction.None
            val action = toolOrNull(token) ?: return@mapNotNull null
            // A tool holding to itself is a tap done slowly; drop it rather than
            // firing the same action twice for one gesture.
            if (tool == action) null else tool to ToolHoldAction.Run(action)
        }?.toMap().orEmpty()

    fun encode(map: Map<ToolbarTool, ToolHoldAction>): String =
        map.entries.joinToString(",") { (tool, action) ->
            val token = when (action) {
                ToolHoldAction.None -> NONE_TOKEN
                is ToolHoldAction.Run -> action.tool.name
            }
            "${tool.name}=$token"
        }

    private fun toolOrNull(name: String): ToolbarTool? =
        runCatching { ToolbarTool.valueOf(name) }.getOrNull()
}

/**
 * The built-ins the Themes tool starts with: a spread of dark, light, AMOLED
 * and the animated ones, small enough to scan mid-typing.
 */
val DefaultThemesPanelBuiltIns: Set<String> = setOf(
    "builtin_ocean",
    "builtin_pitch",
    "builtin_snow",
    "builtin_nebula",
    "builtin_sunset_drift",
    "builtin_aurora",
)

/**
 * How the toolbox draws its tools.
 *
 * [ICONS] is the original grid: a round icon per tool with its name beneath,
 * [KeyboardSettings.toolboxColumns] to a row. [PILLS] draws each tool as a
 * wide rounded row instead — icon on the left, name beside it, and a chevron
 * on the right for the tools that open something. Fewer fit on screen, but
 * every one is readable without squinting at a caption.
 */
enum class ToolboxLayout { ICONS, PILLS }

/**
 * How the toolbox is laid out and paged. Its own class rather than flat fields
 * on [KeyboardSettings] for the reason [ToolbarBehavior] gives: that
 * constructor is at the JVM's argument ceiling. Each field still persists
 * under its own DataStore key.
 *
 * [KeyboardSettings.toolboxColumns] and [KeyboardSettings.toolboxOrder] stay
 * where they are — they predate this class and moving them would orphan every
 * existing caller for no gain.
 */
data class ToolboxSettings(
    /** Icon grid or pill rows (see [ToolboxLayout]). */
    val layout: ToolboxLayout = ToolboxLayout.ICONS,
    /** Pills per row, when [layout] is [ToolboxLayout.PILLS]. */
    val pillColumns: Int = 2,
    /**
     * Fill each pill with the tool's accent colour and draw the icon white on
     * top, instead of tinting just the icon and leaving the pill neutral. Only
     * has an effect while [KeyboardSettings.coloredToolIcons] is on — with the
     * colours off there is nothing to fill with.
     */
    val pillFilled: Boolean = false,
    /**
     * Swipe sideways through fixed pages instead of scrolling vertically.
     * Applies to both layouts.
     */
    val paginate: Boolean = false,
    /** Tools per page while [paginate] is on. */
    val pageSize: Int = 12,
    /**
     * Size of the caption under each toolbox tool, in sp. 0 means "whatever
     * the toolbar labels are set to", which is the default and keeps the two
     * label sizes in step.
     *
     * The toolbar's own labels got a slider and these did not, so a user who
     * enlarged one was left with two label sizes that disagreed.
     */
    val labelSizeSp: Int = 0,
    /**
     * Tools left out of the toolbox grid while staying switched on everywhere
     * else: the toolbar, a search by name, selection actions, hardware
     * shortcuts. For someone who only ever reaches a tool one of those other
     * ways and would rather the grid were one cell shorter. Empty by default.
     *
     * Separate from [KeyboardSettings.enabledTools], which turns a tool off
     * outright. A hidden tool that is pinned stays on the bar.
     */
    val hiddenTools: Set<ToolbarTool> = emptySet(),
) {
    /** The caption size to draw at, resolving 0 against the toolbar's setting. */
    fun labelSizeOr(toolbarLabelSize: Int): Int =
        if (labelSizeSp > 0) labelSizeSp else toolbarLabelSize
}

/**
 * Tools a toolbox page may hold. The floor is one full row of the widest icon
 * grid; the ceiling is well past what fits any phone, because a page taller
 * than the panel simply scrolls — the setting is "how many before the swipe",
 * not "how many are visible".
 */
val ToolboxPageSizeRange = 4..40

/**
 * How the suggestion strip is reachable from a physical keyboard, where nothing
 * is tappable.
 */
enum class SuggestionHotkeyMode(@StringRes val labelRes: Int) {
    OFF(CommonR.string.common_off),

    /** The leader, then a digit. Collides with nothing, at the cost of one extra key. */
    LEADER_DIGIT(R.string.core_settings_suggestion_hotkey_leader_digit_label),

    /**
     * Alt+1 … Alt+9 directly. One keystroke, but browsers, editors and chat apps
     * all claim modifier+digit for tab and workspace switching, so it is opt-in.
     */
    ALT_DIGIT(R.string.core_settings_suggestion_hotkey_alt_digit_label),
}

/**
 * How long the armed picker may wait, in milliseconds. The floor is about as
 * fast as anyone can read one badge; the ceiling is half a minute, past which
 * "armed" stops meaning anything and the next digit typed opens a tool.
 */
val PickerTimeoutRange = 1000..30_000

/**
 * Physical-keyboard shortcuts and panel navigation: opening a tool and driving
 * it without touching the screen. Grouped into their own class rather than
 * sitting flat on [KeyboardSettings] because that class's primary constructor is
 * at the JVM's 255-argument ceiling — each field still persists under its own
 * DataStore key via the matching setter.
 *
 * The older flat [KeyboardSettings.hardwareKeyboardInput] stays where it is: it
 * governs *typing*, which these do not touch.
 */
data class HardwareKeyboardSettings(
    /**
     * Master switch for the leader key and its tool letters. On by default: the
     * default leader is a double-tapped modifier, which produces no character
     * and is passed through to the app either way.
     */
    val shortcutsEnabled: Boolean = true,
    /**
     * Arrow keys move a highlight through an open panel, Enter picks it. Without
     * this, a shortcut can open a tool but not use one.
     */
    val panelNavigation: Boolean = true,
    /**
     * Arrow keys move a ring over the *keys* themselves, and Enter (or the
     * D-pad's centre button) types the one it is on.
     *
     * Off everywhere but a television, where `applyTelevision` turns it on:
     * a remote has no other way to reach a key, while on a phone or a laptop
     * the arrow keys belong to the app's own text field and an IME that ate
     * them would break every cursor movement. [panelNavigation] is the same
     * idea one layer in, and stays independent — a hardware-keyboard user may
     * well want a ring inside the emoji grid and nothing over their letters.
     */
    val dpadKeyNavigation: Boolean = false,
    /**
     * The user has never touched [dpadKeyNavigation], so a television is free
     * to turn it on. Derived from the DataStore key's presence, like
     * [LayoutBehaviorSettings.numberRowUntouched] and for the same reason:
     * without it, a TV user could never switch the ring off.
     *
     * Not persisted itself.
     */
    val dpadKeyNavigationUntouched: Boolean = true,
    /**
     * Escape closes an open panel. Only ever consumed when the keyboard actually
     * has something open — a bare Escape belongs to the app, which may be a
     * browser loading a page or an editor leaving insert mode.
     */
    val escClosesPanel: Boolean = true,
    val suggestionHotkeys: SuggestionHotkeyMode = SuggestionHotkeyMode.ALT_DIGIT,
    /**
     * Digits under the suggestions whenever a physical keyboard is attached,
     * rather than only while the picker is armed. The strip is the one thing a
     * hardware-keyboard user looks at constantly, so its keys are worth the ink.
     */
    val suggestionHintsAlways: Boolean = true,
    /**
     * Ctrl+1 … Ctrl+9 open the toolbar tools with no leader first. Off is a real
     * choice: browsers use exactly these to switch tabs, and while a text field
     * has focus the keyboard would win.
     */
    val toolbarDigitChord: Boolean = true,
    /**
     * Command and Option behave as they do on a Mac: Cmd+C copies, Cmd+left goes
     * to the start of the line, Option+Backspace deletes a word. Off by default —
     * on a PC keyboard the Meta key is the Search/Windows key and belongs to the
     * system.
     */
    val macShortcuts: Boolean = false,
    /**
     * Ctrl+Space cycles the input language forward, Ctrl+Shift+Space backward;
     * holding Ctrl browses the list and releasing commits. On by default:
     * switching language is core to a multilingual keyboard, and the chord is
     * the established convention (ChromeOS, Windows). The cost is real but
     * narrow — code editors use Ctrl+Space for completions (it sits in
     * `ReservedChords` for that reason) — so the toggle stays for the people
     * it bites. The dedicated language-switch keycode needs no toggle and
     * always works.
     */
    val languageSwitchChord: Boolean = true,
    /**
     * Badges spell their modifier out — `Ctrl+1`, `Shift+Q` — instead of using
     * the `⌃` and `⇧` glyphs. On by default: those glyphs are a Mac keycap
     * convention, and a keyboard that does not print them makes the badge a
     * puzzle. Off is for anyone who would rather the badges took less room.
     */
    val hintModifierWords: Boolean = true,
    /**
     * A shortcut that opens a tool also shows the keyboard, which a physical
     * keyboard usually hides. Restored to however it was as soon as the tool closes.
     */
    val autoShowUi: Boolean = true,
    /**
     * What arms the tool picker, in the canonical text form parsed by
     * `HardwareShortcuts.parseLeader` — `"doubletap:ctrl"` or `"ctrl+shift+K"`.
     * Kept as a string so this class needs no `KeyEvent` and the DataStore
     * round-trip is the identity.
     */
    val leader: String = "doubletap:ctrl",
    /**
     * How long the armed picker waits for its key.
     *
     * This is also how long the badges stay on screen, which is the thing people
     * actually notice — three seconds was enough to act on a key you already
     * knew and nowhere near enough to read a bar full of new ones.
     */
    val pickerTimeoutMs: Int = 8000,
    /**
     * Letter → the tool it opens, the complete map rather than a delta: the
     * default is non-empty, so "absent means default" could never express the
     * user unbinding a letter.
     */
    val toolByLetter: Map<Char, ToolbarTool> = DefaultToolLetters,
)

/**
 * Fine-grained feedback gates that don't fit the master haptic/sound toggles:
 * which key events buzz, whether a copy shows a toast, and whether Do Not
 * Disturb mutes haptics. Grouped into their own class rather than sitting flat
 * on [KeyboardSettings] because that class's primary constructor is at the
 * JVM's 255-argument ceiling (see the class doc). Each field still persists
 * under its own DataStore key via the matching setter. Read as
 * `settings.feedback.vibrateOnSpace`, etc.
 */
data class FeedbackSettings(
    /**
     * Buzz on space-bar presses. Off lets heavy space users silence just that
     * key while every other key still vibrates. The key sound (if on) still
     * plays. On by default.
     */
    val vibrateOnSpace: Boolean = true,
    /**
     * Buzz on each word removed by a swipe-to-delete on the backspace key. Off
     * makes clearing a sentence one smooth pull with no per-word buzz-saw. The
     * plain backspace tap and its hold-to-repeat are unaffected. On by default.
     */
    val vibrateOnDeleteSwipe: Boolean = true,
    /**
     * Buzz on every auto-repeat while a key is held (backspace/space repeat).
     * Off keeps only the first press buzzing; the repeats stay silent (their
     * key sound, if on, still plays). On by default.
     */
    val vibrateOnRepeat: Boolean = true,
    /**
     * Click on every auto-repeat while a key is held, the sound counterpart of
     * [vibrateOnRepeat]. On by default, which is what the keyboard has always
     * done; off is for people who hold backspace with the sound on and would
     * rather not hear it machine-gun.
     */
    val soundOnRepeat: Boolean = true,
    /**
     * Let the system's own "touch feedback" switch silence the keyboard's
     * haptics along with everything else.
     *
     * Off by default, which keeps the long-standing behaviour: the keyboard
     * passes `FLAG_IGNORE_GLOBAL_SETTING`, so its buzz survives turning system
     * touch vibration off. That is right for the people who switch the system
     * setting off to quiet *other* apps, and wrong for the ones who expect one
     * switch to cover the phone.
     */
    val respectSystemTouchFeedback: Boolean = false,
    /**
     * Show a short toast confirming text was copied to the clipboard, for
     * fields that give no visual copy feedback of their own. Off by default.
     */
    val toastOnCopy: Boolean = false,
    /**
     * Suppress all keyboard haptics while the system is in Do Not Disturb, so a
     * silenced phone stays fully quiet in the pocket. Off by default (DND
     * targets notifications, not touch feedback, so haptics keep firing).
     */
    val hapticsRespectDnd: Boolean = false,
)

/**
 * Clipboard/undo/redo shortcuts a letter key offers on long press
 * (A/C/V/X/Z/Y). Grouped into their own class rather than sitting flat on
 * [KeyboardSettings] because that class's primary constructor is at the
 * JVM's 255-argument ceiling (see the class doc). Each field still persists
 * under its own DataStore key via the matching setter. Read as
 * `settings.longPressLetterActions.selectAll`, etc.
 *
 * All on by default, which they were not while each one *replaced* that key's
 * accent popup. They are entries in that popup now, appended after the accents
 * the layout lists, so a bound key keeps everything it had and gains one entry:
 * there is no longer a trade to opt into.
 */
data class LongPressLetterActions(
    val selectAll: Boolean = true,
    val copy: Boolean = true,
    val paste: Boolean = true,
    val cut: Boolean = true,
    val undo: Boolean = true,
    val redo: Boolean = true,
    /**
     * Which key carries each action, as six characters in the order the fields
     * above are declared: select-all, copy, paste, cut, undo, redo.
     *
     * Editable because the shipped `acvxzy` is a Latin answer. On a Bengali or
     * Russian layout there is no `a` key to hold, so every one of the six
     * switches above did nothing at all and said nothing about why. Anything
     * other than six characters falls back to the default, so a half-typed
     * value cannot silently unbind the lot.
     */
    val letters: String = DEFAULT_LONG_PRESS_LETTERS,
    /**
     * Puts the action at the *front* of that key's popup instead of the end, so
     * a plain hold-and-release runs it and the accents move one along.
     *
     * The trade the note above says there is no longer any need to make, offered
     * back to the person who does want it: someone who holds `c` to copy far
     * more often than to type ç is, with the default order, sliding past the
     * accents every time. Off by default, because on it changes what a hold has
     * always committed. Only meaningful with
     * [KeyPopupSettings.alternatesHoldToSelect] on, which is where "the first
     * entry is what a plain hold commits" comes from.
     */
    val actionFirst: Boolean = false,
    /**
     * Press 🌐 and slide onto one of the six [letters] to run its action on
     * release: onto `c` copies, onto `v` pastes, and so on. The same six
     * actions as the long press, on the same keys, and independent of the six
     * switches above (those decide what each key's popup offers). A drag that
     * starts once the 🌐 hold has already opened the language picker is left to
     * the picker. Off by default: it changes what a drag off 🌐 does.
     */
    val globeDrag: Boolean = false,
) {
    /**
     * The action bound to the key that types [text], as an index into
     * [letters] in declaration order, or -1 when that key carries none.
     */
    fun actionFor(text: String): Int {
        val ch = text.singleOrNull()?.lowercaseChar() ?: return -1
        for (i in DEFAULT_LONG_PRESS_LETTERS.indices) {
            if (letterFor(i)?.lowercaseChar() == ch) return i
        }
        return -1
    }

    /**
     * The key [action] is bound to, or null when this value is malformed.
     * [action] is an index into [letters] in the declaration order above.
     */
    fun letterFor(action: Int): Char? =
        letters.takeIf { it.length == DEFAULT_LONG_PRESS_LETTERS.length }
            ?.getOrNull(action)
            ?: DEFAULT_LONG_PRESS_LETTERS.getOrNull(action)
}

/** Select-all, copy, paste, cut, undo, redo, on the keys a QWERTY user expects. */
const val DEFAULT_LONG_PRESS_LETTERS = "acvxzy"

/**
 * Whether the keyboard replaces a word it thinks is wrong, and how sure it has
 * to be first.
 *
 * What autocorrect *does* once it fires — the undo chip, what it learns from a
 * manual fix — lives with the engine in `:core:prediction`; this is only the
 * user's half of the deal.
 *
 * Grouped because `KeyboardSettings` is at the JVM's 255-argument ceiling for
 * the `copy$default` Kotlin generates for it — see the note on
 * [CameraSettings]. The DataStore keys stay flat.
 */
data class AutocorrectSettings(
    /** Whether a committed word may be replaced at all. */
    val enabled: Boolean = true,
    /**
     * How sure autocorrect must be before it replaces a word: the factor by
     * which the best candidate has to outscore the runner-up. Low corrects
     * eagerly, high only on near-certainty. Mirrors
     * `SuggestionEngine.DEFAULT_AUTOCORRECT_CONFIDENCE`, spelled out here
     * because prediction already depends on this package.
     */
    val confidence: Float = 4f,
    /**
     * Scale the confidence gate by the user's recent revert rate: a keyboard
     * whose corrections keep getting undone demands more certainty before
     * forcing anything. [confidence] stays the anchor either way.
     */
    val adaptive: Boolean = true,
    /** Backspace right after an autocorrect puts the typed word back. */
    val revertOnBackspace: Boolean = true,
    /**
     * How long an undone correction stays undone. See [UndoMemory]; the
     * levels are named there rather than here because the store they steer
     * ([CorrectionStats]) is what actually implements them.
     */
    val undoMemory: UndoMemory = UndoMemory.NORMAL,
    /** Never autocorrect a word typed all in capitals (acronyms, shouting). */
    val skipAllCaps: Boolean = true,
)

/**
 * The characters the keyboard types that the user did not: capitals at the
 * start of a sentence, the apostrophe in "aren't", the full stop a double
 * space stands for.
 *
 * Grouped because `KeyboardSettings` is at the JVM's 255-argument ceiling for
 * the `copy$default` Kotlin generates for it — see the note on
 * [CameraSettings]. The DataStore keys stay flat.
 */
data class AutoTextSettings(
    /** Fix missing apostrophes on commit: arent → aren't, im → I'm. */
    val apostrophe: Boolean = true,
    /** Capitalize the first letter of a sentence. */
    val capitalize: Boolean = true,
    /** Double-tapping space commits ". " in place of the two spaces. */
    val doubleSpacePeriod: Boolean = true,
    /** Double-tapping space inserts a tab character (wins over the period). */
    val doubleSpaceTab: Boolean = false,
    /**
     * Type a space by itself after sentence and clause punctuation, so
     * "hello,world" becomes "hello, world" without reaching for the spacebar.
     *
     * Off by default: it changes what a keypress produces, which is the one
     * kind of help that has to be asked for. Only plain text fields are
     * touched — an address, an email or a password is structured text where an
     * inserted space is a typo, not a courtesy — and typing a space yourself
     * right after one is inserted does not double it up.
     */
    val spaceAfterPunctuation: Boolean = false,
    /**
     * Take back a space typed in front of a punctuation mark, so "Hey ." lands
     * as "Hey." and "yes , no" as "yes, no".
     *
     * On by default (#206): a space in front of a mark is a slip far more often
     * than a choice, the rule is narrow (only after a word, a number or a
     * closing bracket), and one backspace right after puts the space back.
     * French typography puts a space in front of `?`, `!`, `:` and `;` on
     * purpose. That is [languagePunctuationSpacing]'s business now, and it wins
     * over this rule mark by mark, so a French writer no longer has to edit
     * [hugPunctuationMarks] or give the rule up in their other languages.
     */
    val hugPunctuation: Boolean = true,
    /**
     * The marks [hugPunctuation] pulls a space out from in front of, one
     * character each with no separators. Defaults to the sentence and clause
     * marks, danda included.
     */
    val hugPunctuationMarks: String = HUG_PUNCTUATION_MARKS_DEFAULT,
    /**
     * Type the space a language puts in *front* of a punctuation mark, and keep
     * every other rule from taking it back: French "Bonjour !", "Quoi ?",
     * "voici :" (#215).
     *
     * On by default, and it is not a mode that switches itself on: a language
     * either declares the marks (`LanguageDef.spacedPunctuation`) or it does
     * not, and today only French does. Everybody else types into exactly the
     * keyboard they had. For a French writer who would rather have the marks
     * hug, this is the one switch that turns it off.
     */
    val languagePunctuationSpacing: Boolean = true,
) {
    companion object {
        /**
         * Written as escapes, not as the marks themselves. Four of them are
         * Arabic, and a literal here would reorder this line on screen in
         * every editor that honours bidi — the list would read in an order
         * that is not the order it is stored in.
         *
         * The Arabic marks (comma, semicolon, question mark, and the full stop
         * Urdu and Sindhi write) were missing until #215: Arabic hugs its
         * punctuation exactly as English does, so a space slipped in front of
         * one used to stay there while the same slip in front of "?" was
         * caught.
         */
        const val HUG_PUNCTUATION_MARKS_DEFAULT = ".,?!;:\u0964\u060C\u061B\u061F\u06D4"

        /** Longest mark list the app stores. */
        const val HUG_PUNCTUATION_MARKS_MAX = 24
    }
}

/**
 * Where the suggestion strip's contents come from, and which fields it is
 * allowed to appear in. How the strip *looks*, and what the primary slot does,
 * is [SuggestionStripSettings].
 *
 * Grouped because `KeyboardSettings` is at the JVM's 255-argument ceiling for
 * the `copy$default` Kotlin generates for it — see the note on
 * [CameraSettings]. The DataStore keys stay flat.
 */
data class SuggestionSourceSettings(
    /**
     * Show the suggestion strip even in fields that ask the IME to stay quiet
     * (the NO_SUGGESTIONS flag, email/URI/filter boxes). Many apps — Instagram,
     * Google Keep — set that flag on ordinary text fields; on (the default),
     * the keyboard shows suggestions anyway, the way most keyboards quietly do.
     * Off respects the app and hides the strip. Password fields are always
     * excluded regardless. Autocorrect, gesture typing and Avro composing are
     * governed separately (KeyboardUiState.allowsTypingIntelligence) and keep
     * working whichever way this is set.
     */
    val inAllFields: Boolean = true,
    /** Suggest names from the phone's contacts (needs the Contacts permission). */
    val contacts: Boolean = false,
    /**
     * Complete a contact's email address as you type the start of it — "john"
     * offers john.doe@gmail.com. Needs the Contacts permission.
     */
    val contactEmails: Boolean = false,
    /**
     * Show those email completions inside email fields too, even when the app
     * has asked for no suggestion strip (which email fields normally do). Only
     * matters while [contactEmails] is on.
     */
    val contactEmailsInEmailFields: Boolean = true,
    /** Suggest the names of installed apps ("sign" → Signal). No permission needed. */
    val appNames: Boolean = false,
    /**
     * Words the user never wants suggested or autocorrected to. Matched
     * case-insensitively; the word can still be typed and committed, it is
     * only kept out of the suggestion strip. Empty by default.
     */
    val blacklist: Set<String> = emptySet(),
    /**
     * Words blocked in one language only, keyed by language id (#136). A word
     * here is kept out of the strip while that language is being typed and
     * offered as usual in every other; [blacklist] is the list that applies
     * everywhere. Stored lowercased like [blacklist].
     */
    val blacklistByLanguage: Map<String, Set<String>> = emptyMap(),
    /**
     * Which list a word blocked from the keyboard itself — "Never suggest" on
     * a held word, or a deleted word that only a downloaded list still knows —
     * lands on. The settings editor lets the user pick per word; the keyboard
     * has no room to ask, so it reads this.
     */
    val blacklistScope: BlacklistScope = BlacklistScope.ALL_LANGUAGES,
    /** Typing ":" then a word searches emoji in the suggestion strip (:smi → 😄). */
    val inlineEmojiSearch: Boolean = true,
    /**
     * Show password-manager entries from the system autofill service in the
     * suggestion strip (Android 11+). The chips are rendered by the manager
     * itself; the keyboard only gives them the space. Chips the *platform*
     * sends down the same API (smart replies) are a separate lane with its own
     * toggle, [SuggestionStripSettings.systemSmartReplies].
     */
    val inlineAutofill: Boolean = true,
) {
    /**
     * Every word blocked while [languageId] is being typed: the global list
     * plus that language's own. The global set itself when no language has a
     * list, so the common case allocates nothing per keystroke.
     */
    fun blacklistFor(languageId: String): Set<String> {
        val own = blacklistByLanguage[languageId]
        return if (own.isNullOrEmpty()) blacklist else blacklist + own
    }

    /** True when [word] is blocked while [languageId] is being typed (case-insensitive). */
    fun blacklisted(word: String, languageId: String): Boolean {
        val lower = word.lowercase()
        return lower in blacklist || blacklistByLanguage[languageId]?.contains(lower) == true
    }

    /** True when [word] is blocked anywhere — globally or in any one language. */
    fun blacklistedAnywhere(word: String): Boolean {
        val lower = word.lowercase()
        return lower in blacklist || blacklistByLanguage.values.any { lower in it }
    }

    /** How many entries the blacklist holds in all, counting a word once per list it is on. */
    val blacklistCount: Int
        get() = blacklist.size + blacklistByLanguage.values.sumOf { it.size }
}

/**
 * Which list a word blocked from the keyboard's own menu goes on (#136).
 *
 * The keyboard cannot ask per word — the menu is a hold on a chip — so the
 * choice is made once here. The settings editor asks every time.
 */
enum class BlacklistScope {
    /** The word is blocked whatever language is being typed. The default, and the old behaviour. */
    ALL_LANGUAGES,

    /** The word is blocked only in the language it was blocked from. */
    CURRENT_LANGUAGE,
}

/**
 * The weather tool: the units it reports in and the place it reports for.
 *
 * Grouped because `KeyboardSettings` is at the JVM's 255-argument ceiling for
 * the `copy$default` Kotlin generates for it — see the note on
 * [CameraSettings]. The DataStore keys stay flat.
 */
data class WeatherSettings(
    /** Report temperatures in °F rather than °C. */
    val fahrenheit: Boolean = false,
    /** Saved location; null until the tool has been given one. */
    val latitude: Float? = null,
    val longitude: Float? = null,
    /** What to call [latitude]/[longitude] in the tool's header. */
    val placeName: String = "",
    /**
     * Fetch the forecast the moment a weather chip needs it. Off, the chip
     * waits for a tap first, so typing a weather question never reaches the
     * network by itself. The panel fetches on open either way. Off on F-Droid,
     * like [RateSourceSettings.autoFetch].
     */
    val autoFetch: Boolean = !BuildConfig.ENABLE_FDROID,
)

/**
 * The calendar tool: which other calendars ride along with the Gregorian one,
 * and which days it tints as the weekend.
 *
 * Grouped because `KeyboardSettings` is at the JVM's 255-argument ceiling for
 * the `copy$default` Kotlin generates for it — see the note on
 * [CameraSettings]. The DataStore keys stay flat.
 */
data class CalendarToolSettings(
    /**
     * The two calendars the tool shows alongside the Gregorian one, in the
     * order they are drawn. The first is also what the day cells get their
     * small second number from. Either may be [AltCalendar.NONE].
     */
    val altOne: AltCalendar = AltCalendar.NONE,
    val altTwo: AltCalendar = AltCalendar.NONE,
    /**
     * Days the month grid tints as the weekend. Starts from the device's region
     * (see [Weekend.forRegion]) rather than a fixed pair, since which days are
     * the weekend is exactly the sort of thing that differs by where you are.
     */
    val weekend: Weekend = Weekend.SAT_SUN,
    /** Day offset applied to the tabular Hijri date (moon-sighting drift). */
    val hijriAdjustDays: Int = 0,
)

/**
 * The tools that read a sensor rather than a network: torch, compass, spirit
 * level, moon phase.
 *
 * Grouped because `KeyboardSettings` is at the JVM's 255-argument ceiling for
 * the `copy$default` Kotlin generates for it — see the note on
 * [CameraSettings]. The DataStore keys stay flat.
 */
data class SensorToolSettings(
    /** Turn the torch off automatically when the keyboard is dismissed. */
    val flashlightAutoOff: Boolean = true,
    /** Print the heading in degrees under the compass rose. */
    val compassDegrees: Boolean = true,
    /** Mark the direction of the Kaaba on the compass (needs the saved location). */
    val compassQibla: Boolean = false,
    /** Print the pitch/roll angles on the spirit level. */
    val levelAngles: Boolean = true,
    /**
     * Mirrors the moon drawing for southern-hemisphere viewers. Starts from the
     * device's region (see [isSouthernHemisphere]) rather than false, since
     * which way a crescent faces is a fact about where you are and not a taste;
     * left as it was, half the world is shown the wrong moon until it notices.
     */
    val moonSouthern: Boolean = false,
)

/**
 * The camera-backed scanners and the QR generator: what they do with what they
 * read, and what the codes they draw look like.
 *
 * Grouped because `KeyboardSettings` is at the JVM's 255-argument ceiling for
 * the `copy$default` Kotlin generates for it — see the note on
 * [CameraSettings]. The DataStore keys stay flat.
 */
data class ScannerSettings(
    /** Copy scanned document pages into Pictures/WM Keyboard. */
    val docSaveToGallery: Boolean = false,
    /** Copy generated QR codes into Pictures/WM Keyboard. */
    val qrSaveToGallery: Boolean = false,
    /** How generated QR codes are sent. */
    val qrSendMode: MediaSendMode = MediaSendMode.IMAGE,
    /** Text scanner results start with every word selected (deselect to trim). */
    val ocrAutoSelectWords: Boolean = true,
    /** Which engine the text scanner reads with; see [OcrEngine]. */
    val ocrEngine: OcrEngine = OcrEngine.AUTO,
    /** Vibrate when the QR scanner spots a code. */
    val qrScanHaptics: Boolean = true,
    /** Insert a scanned code into the field the moment it is spotted. */
    val qrScanAutoInsert: Boolean = false,
    /** Fetch the page title/description for a scanned link, like clipboard link previews. */
    val qrScanLinkPreviews: Boolean = false,
    /** Side length of the QR image the generator inserts, in pixels. */
    val qrSizePx: Int = 1024,
    /** Error correction of a generated QR code; see [QrEccLevel]. */
    val qrEcc: QrEccLevel = QrEccLevel.M,
)

/**
 * The GIF and sticker search tools: who they ask, how much they ask for, and
 * how a pick is sent.
 *
 * Grouped because `KeyboardSettings` is at the JVM's 255-argument ceiling for
 * the `copy$default` Kotlin generates for it — see the note on
 * [CameraSettings]. The DataStore keys stay flat.
 */
data class GifSettings(
    /**
     * User-supplied API keys, overriding any key baked into the build.
     * Blank means "use the built-in key" (which may itself be blank).
     */
    val klipyApiKey: String = "",
    val giphyApiKey: String = "",
    /** Provider rating level results are filtered to; see [GifContentFilter]. */
    val contentFilter: GifContentFilter = GifContentFilter.MEDIUM,
    /** Tabs per provider vs one evenly-mixed grid, when several have keys. */
    val sourceMode: GifSourceMode = GifSourceMode.TABS,
    /** GIF/sticker results per search or trending fetch (local packs exempt). */
    val resultLimit: Int = 24,
    /** How GIF picks are sent. Sticker mode only applies to WebP-backed GIFs. */
    val sendMode: MediaSendMode = MediaSendMode.IMAGE,
    /**
     * Offer your own stickers while typing: a sticker's full title, one of
     * its keywords, or an emoji it carries (#329). Only in a field that takes
     * images, so only in the places a sticker could be sent.
     */
    val stickerSuggest: Boolean = true,
    /** Where those stickers show up; see [StickerSuggestStyle]. */
    val stickerSuggestStyle: StickerSuggestStyle = StickerSuggestStyle.TRAY,
    /** What sending one does to the text that asked for it; see [StickerTriggerAction]. */
    val stickerSuggestTrigger: StickerTriggerAction = StickerTriggerAction.DELETE,
)

/**
 * The web, image and encyclopedia search tools.
 *
 * Grouped because `KeyboardSettings` is at the JVM's 255-argument ceiling for
 * the `copy$default` Kotlin generates for it — see the note on
 * [CameraSettings]. The DataStore keys stay flat.
 */
data class WebSearchSettings(
    /**
     * User-supplied API key, overriding any key baked into the build. Blank
     * means "use the built-in key" (which may itself be blank).
     */
    val braveApiKey: String = "",
    /** SafeSearch for the web and image search tools. */
    val safe: Boolean = true,
    /** Results per web/image search (the API caps a page at 10). */
    val resultCount: Int = 8,
    /** Wikipedia subdomain the encyclopedia tool reads (en, bn, de …). */
    val wikiLanguage: String = "en",
    /** Insert Wikipedia links as `[Title](url)` instead of the bare URL. */
    val wikiLinksMarkdown: Boolean = false,
)

data class KeyboardSettings(
    /**
     * The layout being typed on: a [BuiltInLayouts] id, or a custom one. This is
     * the stored choice; [inputMode] below is read off it.
     */
    val activeLayoutId: String = BuiltInLayouts.DEFAULT_ID,
    /** Layouts the 🌐 key and the spacebar swipe cycle between, in order. */
    val enabledLayoutIds: List<String> = BuiltInLayouts.defaultEnabledIds,
    /**
     * Layouts switched to, most recent first (#311). Recorded on every explicit
     * switch whether or not [globeRecentOrder] is on, so turning it on has a
     * history to go by from the first press. May hold layouts no longer
     * enabled; readers filter.
     */
    val recentLayoutIds: List<String> = emptyList(),
    /** User-created layouts, and edits shadowing a built-in by reusing its id. */
    val customLayouts: List<LayoutSpec> = emptyList(),
    /** Languages of [enabledLayoutIds], deduped, in switch order. */
    val enabledLanguages: List<LanguageDef> =
        listOf(LanguageRegistry.byId("en"), LanguageRegistry.byId("bn")),
    /**
     * Secondary languages per primary language id: while typing the primary,
     * these languages' dictionaries also feed suggestions (HeliBoard-style
     * multilingual typing). Empty for everyone by default.
     */
    val secondaryLanguages: Map<String, List<String>> = emptyMap(),
    /**
     * The language of [activeLayoutId], resolved from its layout's `langId`. The
     * registry-era replacement for [inputMode]/[KeyboardLanguage]: dictionary,
     * dictation and handwriting keyed by [LanguageDef.id], and [script] behaviour
     * (direction, case, composer, font) alongside it.
     */
    val language: LanguageDef = LanguageRegistry.byId("en"),
    /** The script [language] writes in — direction, letter-case, composer, font. */
    val script: ScriptDef = ScriptRegistry[ScriptId.LATIN],
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /**
     * Whether the Default theme and the settings app take their palette from
     * the wallpaper (Material You) instead of the app's own.
     *
     * Off since the settings app got a scheme of its own — see `WmLightColors`.
     * A dynamic scheme repaints both surfaces in whatever hue the wallpaper
     * happens to be, which is a fine option to offer and a poor thing to ship
     * as the app's face: on a dynamic default the app has no colour identity at
     * all, and no two phones agree on what it looks like.
     */
    val dynamicColor: Boolean = false,
    /** Selected keyboard theme: [DEFAULT_THEME_ID], a built-in id, or a custom id. */
    val keyboardThemeId: String = DEFAULT_THEME_ID,
    /** User-created themes; built-ins live in code (BuiltInThemes). */
    val customThemes: List<ThemeSpec> = emptyList(),
    /** Light+dark theme pair that follows the system setting; see [AutoThemeSettings]. */
    val autoTheme: AutoThemeSettings = AutoThemeSettings(),
    /**
     * Online photo backgrounds and the rotating background; see
     * [PhotoBackgroundSettings].
     *
     * Grouped rather than flat because of the ceiling: with N fields (none
     * `Long` or `Double`) the generated `copy$default` takes
     * `1 + N + ceil(N/32) + 1` of the JVM's 255 argument slots, capping N at
     * 245. As of 2026-09-10 the class has **190** fields, back off the ceiling
     * it had been sitting on: twelve families came out in one pass
     * ([HapticSettings], [KeySoundSettings], [AccessibilitySettings],
     * [AutocorrectSettings], [AutoTextSettings], [SuggestionSourceSettings],
     * [SensorToolSettings], [WeatherSettings], [CalendarToolSettings],
     * [ScannerSettings], [GifSettings], [WebSearchSettings]). A field past 245
     * does not fail to compile, it fails to load. So: recount before adding a
     * flat field, prefer nesting regardless, and trust
     * `testFullDebugUnitTest` over a green compile.
     *
     * At this headroom the cheapest move for a whole new feature is often not
     * to come here at all. Something the IME never reads can hang off
     * [SettingsRepository] as its own flow for zero slots — see [appLock] and
     * [photoRotationStates] — and still be backed up, because [SettingsBackup]
     * walks the preference map rather than this class.
     */
    val photoBackground: PhotoBackgroundSettings = PhotoBackgroundSettings(),
    val keyHeightDp: Int = 48,
    val numberRowHeightDp: Int = 42,
    /**
     * Room under the bottom key row, above the system navigation bar, or null
     * for the automatic amount, [autoBottomPaddingDp], which depends on what
     * kind of bar is there and so can only be decided where the window insets
     * are known.
     */
    val bottomPaddingDp: Int? = null,
    val splitKeyboard: Boolean = false,
    val splitGapPercent: Int = 12,
    val floatingKeyboard: Boolean = false,
    val floatingWidthDp: Int = 320,
    /** Multiplier on key height while floating, set by the resize grip. */
    val floatingHeightScale: Float = 1f,
    val floatingXFraction: Float = 0.5f,
    val floatingYFraction: Float = 1.0f,
    /**
     * Once shown, the keyboard stays up after the field that opened it is
     * gone, and over windows that have no field at all, until the user hides
     * it (issue #58). Keys reach a fieldless window as key events through the
     * framework's fallback connection, which is what lets an app's keyboard
     * shortcuts be set from the screen. Docked or floating: this only decides
     * whether the window stays, not where it sits.
     */
    val persistentKeyboard: Boolean = false,
    val keyboardWidthPercent: Int = 100,
    val keyboardAlignment: KeyboardAlignment = KeyboardAlignment.CENTER,
    /**
     * Spacing between keys as a multiple of the built-in gap (1 = default).
     * Higher spreads the keys apart (and raises the keyboard, since the gap is
     * part of each row); lower packs them tighter.
     */
    val keyGapScale: Float = 1f,
    val keyCornerRadiusDp: Int = 8,
    val fontScale: Float = 1.0f,
    /**
     * Font for the keyboard's own text: "default" (system), "google:<Name>"
     * (a Google Fonts family, fetched via the GMS fonts provider and cached
     * on-device), or "custom" (an imported font file).
     */
    val keyFontId: String = "default",
    /** Display name of the imported custom font file, for the settings UI. */
    val customFontName: String = "",
    /**
     * Per-script font choice for the non-Latin scripts, keyed by
     * [com.wasimaster.wmkeyboard.core.script.ScriptId] name. Value is "default"
     * (the script's automatic Noto face), "google:<Name>" from that script's
     * curated list, or an imported-file id for the scripts that allow one.
     * Absent scripts use their automatic face. Latin, Cyrillic and Greek follow
     * [keyFontId] and so never appear here.
     */
    val scriptFontIds: Map<String, String> = emptyMap(),
    /**
     * Display names of imported custom font files, keyed the same way as
     * [scriptFontIds]. Only the scripts whose picker offers an import ever have
     * an entry; the Latin one is [customFontName], since it is not per script.
     */
    val customScriptFontNames: Map<String, String> = emptyMap(),
    /**
     * Bumped by the settings app whenever it edits the learned-words file
     * directly, so the IME (which keeps the lexicon in memory) reloads it.
     */
    val lexiconVersion: Int = 0,
    /** Bumped when the settings app imports or removes a custom word list. */
    val customDictVersion: Int = 0,
    /**
     * Let the keyboard fetch the data an enabled language needs — emoji
     * keywords, n-gram packs — on its own, without asking.
     *
     * On by default, because a language whose data never arrives predicts
     * badly and searches emoji only in English, and the packs are small. Off
     * means nothing is ever downloaded unless a button was pressed for it:
     * the prompt shown as a language is added, or the per-item rows under
     * Settings › Languages. Word lists are never fetched automatically either
     * way — they are the megabyte-sized ones, so they are always a choice.
     *
     * **Off by default on F-Droid**, and only there. F-Droid's reviewers check
     * an app against a packet capture taken from first launch, and an app whose
     * listing says the network is something you opt into had better make no
     * connection nobody asked for — however small, however much it improves the
     * predictions. The prompt when a language is added still offers the
     * download, so nothing here becomes unreachable; it becomes a button press.
     * The other channels keep the automatic behaviour.
     */
    val autoDownloadLanguageData: Boolean = !BuildConfig.ENABLE_FDROID,
    /**
     * Link a language you add with the romanized languages of its own script
     * (see [RomanizedPairing]).
     *
     * Only the added language's own pairs are wired, so a link the user
     * removed by hand between two other languages stays removed. Off stops
     * even that, for someone who wants every link made by hand.
     */
    val autoPairRomanized: Boolean = true,
    /**
     * How long a Morse key has to stay quiet before the letter commits, in ms.
     *
     * 750 was a constant, and it is the one number that decides whether the
     * layout is usable: a slow or motor-impaired user loses sequences
     * mid-letter, and a fast one waits. See [MorseCommitMsRange].
     */
    val morseCommitMs: Int = 750,
    /** Emoji look on the keyboard: system pack, Noto (stock Android), or custom. */
    val emojiFont: EmojiFontChoice = EmojiFontChoice.SYSTEM,
    /** Which library face [EmojiFontChoice.INSTALLED] uses; see [EmojiFontSettings]. */
    val emojiFontInstalled: EmojiFontSettings = EmojiFontSettings(),
    /** Key-press vibration: whether, which waveform, how hard (see [HapticSettings]). */
    val haptics: HapticSettings = HapticSettings(),
    /** Per-event haptic gates + copy toast (see [FeedbackSettings]); nested to
     *  stay under the primary-constructor field ceiling. */
    val feedback: FeedbackSettings = FeedbackSettings(),
    /** Key-press sound: whether, which, how loud (see [KeySoundSettings]). */
    val sound: KeySoundSettings = KeySoundSettings(),
    /** Key-preview bubble settings; see [KeyPopupSettings]. */
    val popup: KeyPopupSettings = KeyPopupSettings(),
    // ---- accessibility ----
    /** Contrast, key outlines, screen reader, debounce (see [AccessibilitySettings]). */
    val accessibility: AccessibilitySettings = AccessibilitySettings(),
    /**
     * Suppress non-essential animation across the keyboard and settings app,
     * for vestibular sensitivity. Feedback that carries meaning (the key
     * preview bubble, press colour) is untouched — only motion is removed.
     */
    val reduceMotion: Boolean = false,
    /**
     * The digit row above the letters. On by default: typing a number without
     * it costs a trip through the symbols layer, and every phone screen made
     * this decade has the height for a sixth row. Turning it off is one switch
     * on the wizard's gestures page and in Typing settings.
     */
    val numberRow: Boolean = true,
    /**
     * Sizing overrides per screen shape (landscape, unfolded, both). The
     * plain sizing fields above are the portrait values; anything a variant
     * leaves unset inherits them. Resolve with [resolvedFor].
     */
    val sizingOverrides: Map<ScreenVariant, SizingOverride> = emptyMap(),
    /** Whether a word may be replaced on commit, and how sure first (see [AutocorrectSettings]). */
    val correction: AutocorrectSettings = AutocorrectSettings(),
    /** Capitals, apostrophes and spaces the keyboard types for you (see [AutoTextSettings]). */
    val autoText: AutoTextSettings = AutoTextSettings(),
    val suggestions: Boolean = true,
    // `suggestionsFirst` and `suggestionPrimaryCenter` moved into
    // [SuggestionStripSettings], and the strip's sources into
    // [SuggestionSourceSettings], to keep this constructor under the JVM slot
    // ceiling; their DataStore keys are unchanged.
    /** Where the strip's contents come from, and where it shows (see [SuggestionSourceSettings]). */
    val suggestionSources: SuggestionSourceSettings = SuggestionSourceSettings(),
    val gestureTyping: Boolean = true,
    /**
     * What a letter-area swipe does when [gestureTyping] is on: glide-type a
     * word (default) or draw handwriting recognized on the keyboard itself.
     */
    val letterSwipeAction: LetterSwipeAction = LetterSwipeAction.TYPE_WORDS,
    /**
     * Glide-typing behaviour and trail appearance, grouped (see [GestureSettings]).
     * Nested rather than flattened onto [KeyboardSettings] because the top-level
     * data class is near the JVM's copy() slot ceiling.
     */
    val gesture: GestureSettings = GestureSettings(),
    /** Swipe that starts moving before the long-press delay elapses. */
    val spaceShortSwipe: SpaceSwipeAction = SpaceSwipeAction.LANGUAGE,
    /** Swipe that begins after holding the spacebar past the long-press delay. */
    val spaceLongSwipe: SpaceSwipeAction = SpaceSwipeAction.CURSOR,
    /**
     * Draw ◀ ▶ arrows around the spacebar language name, hinting that a
     * horizontal swipe switches language. Only shown when a swipe slot is
     * actually set to language switching and more than one mode is enabled.
     */
    val spacebarLanguageArrows: Boolean = true,
    /**
     * Text drawn on the spacebar. Blank keeps the current language name;
     * `%s` inside a custom label is replaced by it, so "— %s —" still
     * tracks the mode.
     */
    val spacebarLabel: String = "",
    /**
     * Dragging sideways on backspace deletes whole words instead of
     * repeating single-character deletes.
     */
    val backspaceSwipeDelete: Boolean = true,
    /**
     * Route physical-keyboard keystrokes through the keyboard's own engine —
     * transliteration, the composing buffer, suggestions and autocorrect — so a
     * hardware keyboard types Bengali (or gets corrections) exactly like the
     * on-screen keys. Off types the raw characters straight into the field,
     * letting the system and the physical layout own input. On either way,
     * shortcuts (Ctrl+C), cursor keys and function keys stay with the system.
     */
    val hardwareKeyboardInput: Boolean = true,
    /**
     * Opening and driving the tools from a physical keyboard: the leader key,
     * its tool letters, the focus ring. Separate from [hardwareKeyboardInput],
     * which is only about how typed characters are processed.
     */
    val hardwareKeyboard: HardwareKeyboardSettings = HardwareKeyboardSettings(),
    /** Volume up/down move the text cursor while the keyboard is showing. */
    val volumeCursor: Boolean = false,
    /**
     * Hand the volume keys back to the system while audio is playing, so
     * cursor control never costs you the ability to turn a song down.
     */
    val volumeCursorMediaAware: Boolean = true,
    /** Replace the 🌐 key with an emoji key (language switching moves to spacebar swipes). */
    val globeAsEmoji: Boolean = true,
    /**
     * Draw the 🌐 key on the bottom row at all (issue #139). Off takes it off
     * every layout's bottom row, along with the emoji key [globeAsEmoji] would
     * have put in its place, and gives its width to the spacebar. Language
     * switching stays on the spacebar gestures.
     */
    val showGlobeKey: Boolean = true,
    /**
     * The 🌐 key (and a physical keyboard's language key) goes by recent use
     * rather than switch order (#311): one press goes back to the layout used
     * before this one, and presses in quick succession walk further back
     * through [recentLayoutIds], like Alt+Tab. Off by default.
     */
    val globeRecentOrder: Boolean = false,
    /**
     * List each enabled layout as an Android input-method subtype, so the
     * system language switcher (the "Choose input method" sheet) lists them and
     * can switch between them. Off = the keyboard registers no subtypes and
     * ignores OS subtype switches; language switching then lives entirely
     * in-keyboard (globe / spacebar / picker), and the sheet lists the keyboard
     * under its own name, like keyboards that expose no subtypes at all.
     *
     * Off by default: the switcher draws the subtype label where a subtype-less
     * keyboard draws its name, so one language name in that row reads as the
     * keyboard's name.
     */
    val osLanguageSwitcher: Boolean = false,
    /**
     * Lead the switcher's subtype label with the app name ("WM Keyboard ·
     * English") rather than the bare language. The system decides how it styles
     * the label versus the app name — this only changes what the label itself
     * reads, so it cannot truly swap which is bold. No effect while
     * [osLanguageSwitcher] is off.
     */
    val subtypeAppNameFirst: Boolean = false,
    /** Per-app language/subtype memory (see [PerAppLanguageSettings]). */
    val perAppLanguage: PerAppLanguageSettings = PerAppLanguageSettings(),
    val onboardingDone: Boolean = false,
    /** Persona answers from the onboarding quiz (see [OnboardingSettings]). */
    val onboarding: OnboardingSettings = OnboardingSettings(),
    /** Settings-app screen preferences (see [AppUiSettings]). */
    val appUi: AppUiSettings = AppUiSettings(),
    val rows: RowSettings = RowSettings(),
    val toolLimits: ToolLimitSettings = ToolLimitSettings(),
    /**
     * Language ids whose conjunct clusters backspace as one unit. Per language,
     * not global: someone who types both Bengali and Hindi may well want whole
     * clusters gone in one and code points in the other, and the old single
     * switch made that impossible. Only languages on a cluster-forming script
     * are ever put here.
     */
    val conjunctBackspaceLanguages: Set<String> = emptySet(),
    /** Chinese/Cantonese conversion-IME options (see [CjkSettings] for why nested). */
    val cjk: CjkSettings = CjkSettings(),
    val oneHandedMode: OneHandedMode = OneHandedMode.OFF,
    /** Per-orientation one-handed width, height scale and dock side. */
    val oneHanded: OneHandedSettings = OneHandedSettings(),
    val learnFromTyping: Boolean = true,
    /**
     * Also add words the keyboard learns to Android's system personal
     * dictionary, so other keyboards and spell checkers know them too. Off by
     * default — the on-device lexicon already covers this keyboard.
     */
    val addWordsToSystemDictionary: Boolean = false,
    /** Clipboard-tool history, panel and suggestion-strip settings (see [ClipboardSettings]). */
    val clipboard: ClipboardSettings = ClipboardSettings(),
    /** Suggestion-strip content options — quick-punctuation chips (see [SuggestionStripSettings]). */
    val suggestionStrip: SuggestionStripSettings = SuggestionStripSettings(),
    val longPressDelayMs: Int = 300,
    /** Hold-to-repeat cadence for delete and space; see [KeyRepeatSettings]. */
    val keyRepeat: KeyRepeatSettings = KeyRepeatSettings(),
    /** Small corner label on each key showing its first long-press character. */
    val longPressHints: Boolean = true,
    /** Assorted layout & gesture behaviours (see [LayoutBehaviorSettings]). */
    val layoutBehavior: LayoutBehaviorSettings = LayoutBehaviorSettings(),
    /**
     * A predicted word over the key that would reach it, picked by flicking up
     * on that key (see [OctopusSettings]). Off by default.
     */
    val octopus: OctopusSettings = OctopusSettings(),
    /** Long-pressing A selects all text in the field. */
    /**
     * Send Ctrl+A/C/V/X to the app as raw key events instead of using the
     * clipboard actions.
     *
     * Off by default because performContextMenuAction works in WebViews and
     * Compose text fields, where a raw Ctrl+C reaches nothing at all. A terminal
     * is the opposite case — it needs Ctrl+C to arrive as an interrupt — so this
     * is a setting rather than a guess: EditorInfo cannot tell a terminal from a
     * code editor or a password box.
     */
    val rawClipboardShortcuts: Boolean = false,
    /** Long-press shortcuts on the A/C/V/X/Z/Y keys (see [LongPressLetterActions]). */
    val longPressLetterActions: LongPressLetterActions = LongPressLetterActions(),
    val emojiToolbar: Boolean = true,
    /** Tint each tool icon its own accent colour in Settings and the toolbox. */
    val coloredToolIcons: Boolean = true,
    /**
     * Per-tool accent-colour overrides (ARGB longs), applied when
     * [coloredToolIcons] is on. A tool absent from the map keeps its built-in
     * default (see [com.wasimaster.wmkeyboard.core.ui.toolAccentColor]).
     */
    val toolColorOverrides: Map<ToolbarTool, Long> = emptyMap(),
    /**
     * Paint the tool icons with a two-colour gradient, top left to bottom
     * right, instead of one flat colour. Only meaningful while
     * [coloredToolIcons] is on, which is what paints them at all.
     */
    val toolIconGradients: Boolean = false,
    /**
     * The far end of each tool's gradient (ARGB longs), applied when
     * [toolIconGradients] is on. A tool absent from the map takes an end colour
     * derived from its near one (see
     * [com.wasimaster.wmkeyboard.core.ui.toolAccentEndColor]).
     */
    val toolColorEndOverrides: Map<ToolbarTool, Long> = emptyMap(),
    /** Which glyph each customisable icon draws (see [IconSettings]). */
    val icons: IconSettings = IconSettings(),
    val incognito: Boolean = false,
    val toolbarTools: List<ToolbarTool> = DefaultToolbarTools,
    /** Toolbar enable/behaviour/layout switches (see [ToolbarBehavior]). */
    val toolbarBehavior: ToolbarBehavior = ToolbarBehavior(),
    /**
     * Height of the top toolbar/suggestion strip's content, in dp, before
     * [ToolbarBehavior.paddingTopDp] and [ToolbarBehavior.paddingBottomDp] are
     * added around it. Settings no longer offers a slider for it (#208): the
     * two paddings replaced it, and a theme's own toolbar height still sets it.
     */
    val toolbarHeightDp: Int = 44,
    /** Draw each tool's name under its icon on the toolbar. */
    val toolbarLabels: Boolean = false,
    /**
     * Font size of those toolbar labels, in sp. 9 was small enough that the
     * name under the icon had to be read rather than glanced at, which is the
     * opposite of what turning labels on is for.
     */
    val toolbarLabelSize: Int = 10,
    val toolCircleRadiusDp: Int = 20,
    /**
     * Outline of that background. Shares the key shapes, the way the popups do;
     * a theme may override it ([ThemeSpec.toolShape]). Only the rounded and cut
     * shapes read [toolCircleRadiusDp], and a radius of 0 still means no
     * background, whatever the shape is.
     */
    val toolShape: KeyShapeKind = KeyShapeKind.ROUNDED,
    val commaAsEmoji: Boolean = false,
    /**
     * Swap the comma and 🌐 keys either side of the spacebar, so the bottom row
     * reads `?123 🌐 , ␣ . ⏎`.
     *
     * On by default, which puts the emoji key in the outer slot: whichever of
     * the two [globeAsEmoji]/[commaAsEmoji] turned into the emoji key moves
     * with it, so the emoji key ends up beside `?123` and the comma sits next
     * to the spacebar where a punctuation key belongs.
     */
    val swapCommaAndGlobe: Boolean = true,
    /** History tab of the emoji panel: recently used vs most used. */
    val emojiTabMode: EmojiTabMode = EmojiTabMode.RECENTS,
    /** "Clear recents" button on the emoji panel's history tab. Off by default. */
    val emojiClearRecentsButton: Boolean = false,
    /** Show the emoji's Unicode name at the top of its long-press popup. */
    val emojiLongPressName: Boolean = true,
    /** Emoji candidates in the suggestion strip while typing. */
    val emojiPrediction: Boolean = true,
    val emojiBarMode: EmojiBarMode = EmojiBarMode.OFF,
    val emojiBarContent: EmojiBarContent = EmojiBarContent.MOST_USED,
    /** Whether an emoji suggestion replaces the typed word or follows it. */
    val emojiInsertMode: EmojiInsertMode = EmojiInsertMode.REPLACE,
    /** Emoji options that didn't fit the flat field list (see [EmojiSettings]). */
    val emoji: EmojiSettings = EmojiSettings(),
    /** Tools available anywhere on the keyboard; disabled tools are hidden. */
    val enabledTools: List<ToolbarTool> = ToolbarTool.entries.toList(),
    /**
     * Every tool's position in the toolbox grid, most-used-first by default;
     * the user rearranges it by dragging tools around the toolbox. Always a
     * complete ordering over all tools — pinned/disabled ones keep their
     * rank so they come back where they belong.
     */
    val toolboxOrder: List<ToolbarTool> = DefaultToolOrder,
    /** The toolbox drag hint was dismissed; after that it only rarely reappears. */
    val toolboxHintDismissed: Boolean = false,
    /** How the toolbox draws and pages its tools (see [ToolboxSettings]). */
    val toolbox: ToolboxSettings = ToolboxSettings(),
    /** Torch, compass, spirit level, moon phase (see [SensorToolSettings]). */
    val sensorTools: SensorToolSettings = SensorToolSettings(),
    /** Redo sends Ctrl+Y instead of Ctrl+Shift+Z. */
    val redoUsesCtrlY: Boolean = false,
    /** Units and saved place for the weather tool (see [WeatherSettings]). */
    val weather: WeatherSettings = WeatherSettings(),
    /** The network activity log's two switches (see [NetworkLogSettings]). */
    val networkLog: NetworkLogSettings = NetworkLogSettings(),
    /** Alternate calendars and the weekend, for the calendar tool (see [CalendarToolSettings]). */
    val calendarTool: CalendarToolSettings = CalendarToolSettings(),
    /** Handwriting canvas ignores finger touches; only a stylus draws. */
    val handwritingStylusOnly: Boolean = false,
    /** Pause after the last stroke before recognizing and committing. */
    val handwritingCommitDelayMs: Int = 700,
    /** Insert a space between consecutively handwritten words. */
    val handwritingAutoSpace: Boolean = true,
    /** Voice tool surface and collapsed-bar state, grouped (see [VoiceBarSettings]). */
    val voiceBar: VoiceBarSettings = VoiceBarSettings(),
    /** Keep listening after each dictated sentence. */
    val voiceContinuous: Boolean = true,
    /** Saying "comma" / "দাঁড়ি" types the mark instead of the word. */
    val voiceSpokenPunctuation: Boolean = true,
    /** Offline Whisper dictation settings, grouped (see [CameraSettings] for why). */
    val whisper: WhisperSettings = WhisperSettings(),
    /** Camera tool settings, grouped (see [CameraSettings]). */
    val camera: CameraSettings = CameraSettings(),
    /** App-launcher tool settings, grouped (see [LauncherToolSettings]). */
    val launcher: LauncherToolSettings = LauncherToolSettings(),
    /** Media-control tool settings, grouped (see [MediaControlSettings]). */
    val mediaControl: MediaControlSettings = MediaControlSettings(),
    /** The KDE Connect tool: the link to a paired computer (see [KdeConnectSettings]). */
    val kdeConnect: KdeConnectSettings = KdeConnectSettings(),
    /** Free-software service endpoints for the F-Droid build (see [SelfHostedSettings]). */
    val selfHosted: SelfHostedSettings = SelfHostedSettings(),
    /** How sticker-tool picks are sent. WhatsApp shows real stickers for these. */
    val stickerSendMode: MediaSendMode = MediaSendMode.STICKER,
    /** The document/text/QR scanners and the QR generator (see [ScannerSettings]). */
    val scanner: ScannerSettings = ScannerSettings(),
    /** The GIF and sticker search tools (see [GifSettings]). */
    val gif: GifSettings = GifSettings(),
    /** Dictionary tool looks up the word at the cursor when it opens. */
    val dictionaryAutoLookup: Boolean = true,
    /** Where the Dictionary tool looks, in the order it asks, each on or off. */
    val dictionarySources: List<DictionarySourceChoice> = DictionarySources.DEFAULT,
    /** Text-editing tool and selection-editing settings (see [TextEditingSettings]). */
    val textEditing: TextEditingSettings = TextEditingSettings(),
    /** The trackpad tool: sensitivity and the gestures it answers to (see [TrackpadSettings]). */
    val trackpad: TrackpadSettings = TrackpadSettings(),
    /** The vocabulary tool: nudges, cards, flashcards, audio (see [VocabularySettings]). */
    val vocabulary: VocabularySettings = VocabularySettings(),
    /**
     * Which features are given up to save battery, and what switches that on
     * (see [PowerSavingSettings]). Read the *config*; what is actually in force
     * is the settings object itself, which the service has already put through
     * [underPowerSaving] by the time anyone downstream sees it.
     */
    val powerSaving: PowerSavingSettings = PowerSavingSettings(),
    /**
     * What the keyboard may fetch on a metered connection, and what turns that
     * restriction on (see [DataSaverSettings]). Read the *config* here too:
     * the background fetches have already been taken out of the settings object
     * by [onMeteredNetwork], and the rest is decided at the moment it happens
     * through `DataSaverStatus`.
     */
    val dataSaver: DataSaverSettings = DataSaverSettings(),
    /** Number pad digits calculator-style (789 on top) instead of phone-style (123 on top). */
    val numpadCalculatorLayout: Boolean = false,
    /** Incognito stops the clipboard tool from capturing copies. */
    val incognitoPausesClipboard: Boolean = true,
    /** Incognito stops word and emoji learning. */
    val incognitoPausesLearning: Boolean = true,
    /**
     * Turn incognito on by itself for fields that ask not to be learned from
     * (IME_FLAG_NO_PERSONALIZED_LEARNING) — Chrome incognito tabs, private
     * browsing in other browsers, and password-manager notes fields.
     */
    val autoIncognito: Boolean = true,
    /**
     * Whether Android's own backup is allowed to carry this app's data off the
     * device — to Google's servers, or to a new phone during a device-to-device
     * transfer. Off by default, and honoured by the app's backup agent rather
     * than by the manifest; see [CloudBackup].
     */
    val cloudBackup: Boolean = false,
    /** Decimal places on currency conversion results. */
    val currencyDecimals: Int = 2,
    /**
     * How a currency chip names the currency it converted into: "96.04
     * Rupee", "₹96.04" or "96.04 INR". Coins keep their ticker either way.
     */
    val currencyLabel: CurrencyLabel = CurrencyLabel.NAME,
    /** Hours exchange rates stay fresh before the panel refetches on open. */
    val currencyCacheHours: Int = 6,
    /** Where rates come from, and how cryptocurrency is handled. */
    val rateSources: RateSourceSettings = RateSourceSettings(),
    /** Pause after typing stops before the grammar tool re-lints the field. */
    val grammarDebounceMs: Int = 350,
    /**
     * Unit converter memory: each category's last from/to pair, last-used
     * category first ("Length|m|ft;Mass|kg|lb"). Restored on open.
     */
    val unitConvertLast: String = "",
    /**
     * Read a length in feet as feet and inches — "3 ft 3.37 in" rather than
     * "3.2808399 ft" — in the converter and on the smart chip. Off gives the
     * plain decimal back. See
     * [com.wasimaster.wmkeyboard.core.tools.CompoundUnits].
     */
    val compoundUnits: Boolean = true,
    /** Tools per row in the toolbox grid. */
    val toolboxColumns: Int = 4,
    /** ISO 639-1 code the translate tool translates into (source is auto-detected). */
    val translateTargetLang: String = "en",
    /** Which engine the translate tool uses (see [TranslateSettings]). */
    val translate: TranslateSettings = TranslateSettings(),
    /** English dialect the offline grammar tool checks against. */
    val grammarDialect: GrammarDialect = GrammarDialect.AMERICAN,
    /**
     * Issue kinds the grammar panel leaves out. Empty — the default — shows
     * everything the engine finds; a kind in here is filtered out of the
     * cards, the issue count and "Fix all" alike, so a filtered issue is not
     * one "Fix all" quietly rewrites behind the user's back.
     */
    val grammarHiddenKinds: Set<GrammarLintKind> = emptySet(),
    /**
     * Squiggle spelling errors but offer no fix popup when Harper acts as the
     * system spell checker. Only has an effect on Android 12+, where the
     * framework honours the "mark but don't show suggestions UI" flag.
     */
    val spellCheckerNoSuggestions: Boolean = false,
    /**
     * User-supplied API key for the translate tool, overriding any key baked
     * into the build. Blank means "use the built-in key" (which may itself be
     * blank).
     */
    val translateApiKey: String = "",
    /** The web, image and encyclopedia search tools (see [WebSearchSettings]). */
    val webSearch: WebSearchSettings = WebSearchSettings(),
    /** Recently used special symbols, newest first (symbols tool). */
    val symbolRecents: List<String> = emptyList(),
    /** Dedicated symbol row above the keys (special characters & snippets). */
    val symbolRowEnabled: Boolean = false,
    /** Symbol sets offered by the row's picker chip (built-in or custom ids). */
    val symbolRowSetIds: List<String> = BuiltInSymbolSets.defaultEnabledIds,
    /** Set the row currently shows; the picker chip changes it. */
    val symbolRowActiveSetId: String = BuiltInSymbolSets.PUNCTUATION_ID,
    /** User-created symbol sets; built-ins live in code (BuiltInSymbolSets). */
    val customSymbolSets: List<SymbolSet> = emptyList(),
    /**
     * Top-to-bottom order of the rows above the keys. The emoji row sits
     * above the toolbar by default: it is used far more often than the tool
     * buttons, so it belongs closest to the suggestion strip.
     */
    val barOrder: List<BarRow> = DefaultBarOrder,
    /**
     * Emoji panel takes over the whole keyboard: the toolbar (and any emoji
     * or symbol row) hides and the category tabs move up into the reclaimed
     * row, next to a back button.
     */
    val emojiFullBleed: Boolean = true,
    /** Same treatment for the GIF and sticker panels, with search up top. */
    val mediaFullBleed: Boolean = true,
    /**
     * While a keyboard mode is active, rearranging tools edits that mode's
     * own tool order instead of the global one — otherwise the change would
     * look like it did nothing, since the mode's order wins while it is on.
     */
    val modeToolOrderEdits: Boolean = true,
    /** The "tool order is per-mode" notice has been shown once. */
    val modeToolOrderHintSeen: Boolean = false,
    /**
     * Master switch for the whole modes feature (issue #41).
     *
     * On by default, because the shipped modes are what configure the emoji
     * and symbol rows for a chat box or a password field. Off hides the tool,
     * stops any mode being applied, and leaves the keyboard on the plain
     * globals everywhere — the answer for someone who found their settings
     * quietly different in the next app and read it as the keyboard losing
     * them. The modes themselves are kept, so switching it back on restores
     * every one of them: [withoutModes] is a view, not a write.
     */
    val modesEnabled: Boolean = true,
    /** Keyboard modes (per-app / per-field bundles of overrides). */
    val keyboardModes: List<KeyboardMode> = DefaultKeyboardModes,
    /**
     * Master switch for the smart chips on the suggestion strip — the
     * inline calculator, currency and unit answers plus the tool keywords.
     * The four flags below refine it; this one turns the lot off.
     */
    val smartSuggestions: Boolean = true,
    /** Offer the result when an arithmetic expression is typed. */
    val smartCalc: Boolean = true,
    /** Offer the converted amount when "150 usd" style text is typed. */
    val smartCurrency: Boolean = true,
    /** Offer the converted value when "1 ft" style text is typed. */
    val smartUnits: Boolean = true,
    /** Offer to open a tool when one of its keywords is typed. */
    val smartToolKeywords: Boolean = true,
    /** The contextual chip families (dates, weather, lookups, intents, GIFs). */
    val smartChips: SmartChipSettings = SmartChipSettings(),
    /** One-tap actions for the current selection. Off until it is asked for. */
    val selectionMacros: SelectionMacroSettings = SelectionMacroSettings(),
    /**
     * Per-tool keyword overrides, "TOOL=a,b;TOOL=c". Tools missing from the
     * string use [com.wasimaster.wmkeyboard.core.tools.SmartSuggest.defaultKeywords].
     */
    val toolKeywords: String = "",
    /**
     * The tools whose keywords have to match the typed capitals exactly, as a
     * comma-separated list of [ToolbarTool] names. Everything not listed is
     * matched case-insensitively, which is the default.
     */
    val toolKeywordCase: String = "",
    /** Trig in degrees (off = radians) for the calculator tool. */
    val calcDegrees: Boolean = true,
    /** Calculator keypad with 1 2 3 on the top row, like a dialer (issue #294). */
    val calcPhoneLayout: Boolean = false,
    /** Decimal places in calculator/converter results. */
    val calcPrecision: Int = 8,
    /** Currency codes the converter starts on. */
    val currencyFrom: String = "USD",
    val currencyTo: String = "BDT",
    /** Password/passphrase generator defaults (the panel tweaks these live). */
    val passwordGenerator: PasswordGeneratorSettings = PasswordGeneratorSettings(),
    /** Typing-speed test: its options and its records; see [TypingTestSettings]. */
    val typingTest: TypingTestSettings = TypingTestSettings(),
    /**
     * Count typing statistics — characters, words, backspaces and active
     * time, per day — for the About › Statistics screen. Aggregate numbers
     * only; nothing typed is ever stored. Turning this off stops counting
     * but keeps what was already recorded.
     */
    val typingStatsEnabled: Boolean = true,
    /**
     * Bumped by the settings app whenever it deletes the statistics file, so
     * the IME (which keeps the counters in memory) reloads instead of saving
     * the old numbers straight back. Same contract as [lexiconVersion].
     */
    val statsVersion: Int = 0,
    /** Everything the AI tool owns — see [AiSettings]. */
    val ai: AiSettings = AiSettings(),
    /** The one-time-code chip fed by the notification listener — see [OtpSettings]. */
    val otp: OtpSettings = OtpSettings(),
    /** The backup that writes itself to a folder — see [AutoBackupSettings]. */
    val autoBackup: AutoBackupSettings = AutoBackupSettings(),
)

/**
 * Every setting at the value it shipped with, as one object to read a single
 * default out of: `SettingsDefaults.haptics.enabled`, `SettingsDefaults.otp.enabled`.
 *
 * The settings screens use it for the reset control each row grows once its
 * value stops matching the default. Reading the default off the same data
 * class the setting itself lives on is the point — a default written a second
 * time in the UI is a default that drifts the first time the real one changes,
 * and the row would then offer to "reset" to a value the app never had.
 *
 * Lazy, because building it walks the nested settings objects and the registry
 * lookups behind the language defaults, and nothing needs that before the
 * first screen is drawn.
 */
val SettingsDefaults: KeyboardSettings by lazy { KeyboardSettings() }

/**
 * The one-time-code suggestion chip: a verification code arriving in any app's
 * notification is offered on the suggestion strip, one tap from typed. Grouped
 * (see [CameraSettings] for why); DataStore keys stay flat.
 *
 * Off by default twice over: [enabled] starts false, and the feature also
 * needs the notification-access grant, which the keyboard can only send the
 * user to Settings for. The codes themselves are never persisted — they live
 * in memory until used, dismissed or expired.
 */
data class OtpSettings(
    /** Master switch. Mirrored to the notification listener's own flag. */
    val enabled: Boolean = false,
    /**
     * Only raise the chip when the focused field reads as a code box — it
     * asks for digits, or its hint, label or resource id names it a code.
     *
     * Off by default, and that is the point: a code box that the app built
     * out of a plain text input with no telling name is invisible to any
     * test, and the chip not appearing where the code was wanted is a worse
     * failure than a chip appearing where it was not. On is for people who
     * would rather never see a code offered mid-sentence.
     *
     * Was `numberFieldsOnly`, when the test was the input class alone; the
     * stored key keeps the old name so nobody's choice is lost.
     */
    val codeFieldsOnly: Boolean = false,
    /**
     * How long a captured code stays on offer. Codes outlive their welcome
     * fast: a chip still showing last hour's code is worse than no chip.
     */
    val expiryMinutes: Int = 3,
    /**
     * Cancel the code's notification once the chip is used, so the shade does
     * not keep advertising a code that has already been spent. Uses the same
     * notification-access grant the capture does.
     */
    val dismissNotification: Boolean = false,
    /**
     * Type a code one character at a time rather than committing it whole.
     *
     * The box a code goes into is very often not one box: a row of single-
     * character inputs, each of which takes one character and then moves the
     * focus on by itself. A whole code committed at once lands entirely in the
     * first of them, and everything past the first character is dropped.
     * Typing character by character is what the boxes are built for, and it is
     * indistinguishable from a whole commit in an ordinary single field.
     *
     * Governs every path that types a code: the notification chip, the
     * clipboard code chip, and a code fragment lifted out of a clip.
     */
    val perDigitEntry: Boolean = true,
)

/**
 * Where an automatic backup goes.
 *
 * Eleven destinations, none of them a server of ours. That is the whole shape of
 * this feature: the app writes a file somewhere the user already has, and never
 * holds a copy.
 */
enum class BackupDestination(
    /** Stable on disk. Never store an enum's [name] and hope. */
    val id: String,
) {
    /**
     * A folder picked through the Storage Access Framework. Reaches anything
     * with a `DocumentsProvider`, needs no account of any kind, and works on a
     * device with no Google Play services at all. The default, and the one to
     * suggest first.
     */
    FOLDER("folder"),

    /** A WebDAV server: Nextcloud, ownCloud, or anything else that speaks it. */
    WEBDAV("webdav"),

    /**
     * The app's own hidden folder in the user's Google Drive.
     *
     * Only reachable on a build with Google Play services compiled in, and only
     * after the user authorizes it. [FOLDER] already reaches Drive through the
     * Drive app's own provider; this exists for the case where that app is not
     * installed, and to put the backups somewhere the user cannot delete by
     * tidying up a folder.
     */
    DRIVE("drive"),

    /**
     * A bucket on anything that speaks the S3 API: AWS itself, MinIO on a
     * machine at home, Cloudflare R2, Backblaze B2, Wasabi, Garage.
     *
     * One protocol reaching all of them, with no account of ours and no OAuth
     * dance — the credentials are a key pair the user already has.
     */
    S3("s3"),

    /** The app's own folder in the user's Dropbox, via the App Folder scope. */
    DROPBOX("dropbox"),

    /** The app's own folder in the user's OneDrive, via `Files.ReadWrite.AppFolder`. */
    ONEDRIVE("onedrive"),

    /**
     * An FTP server, with TLS unless the user insists otherwise.
     *
     * The oldest option here and the one with the fewest guarantees, kept
     * because a lot of home NAS boxes and cheap web hosts offer nothing else.
     */
    FTP("ftp"),

    /** An SSH server's SFTP subsystem. See [SftpConfig]. */
    SFTP("sftp"),

    /** A Windows or Samba share, over SMB 2 or 3. See [SmbConfig]. */
    SMB("smb"),

    /** A folder in a GitHub, GitLab, Gitea or Forgejo repository. See [GitConfig]. */
    GIT("git"),

    /** A folder of messages in a mail account. See [ImapConfig]. */
    IMAP("imap"),
}

/**
 * A bucket on an S3-compatible service.
 *
 * Grouped rather than flat inside [AutoBackupSettings] to keep each
 * destination's settings readable next to each other; the DataStore keys stay
 * flat as always.
 */
data class S3Config(
    /**
     * The service endpoint, for example `https://s3.eu-west-1.amazonaws.com`,
     * `https://<account>.r2.cloudflarestorage.com`, or a MinIO address on the
     * local network. Empty means AWS, derived from [region].
     */
    val endpoint: String = "",
    /** Signing region. `us-east-1` is what R2 and most MinIO setups expect. */
    val region: String = "us-east-1",
    val bucket: String = "",
    /** Optional key prefix, so backups can live in a folder inside the bucket. */
    val prefix: String = "",
    val accessKeyId: String = "",
    /** In [SettingsBackup.SECRET_KEYS]. */
    val secretAccessKey: String = "",
    /**
     * Whether to address the bucket as a path (`endpoint/bucket/key`) rather
     * than as a subdomain (`bucket.endpoint/key`).
     *
     * Virtual-hosted style is what AWS prefers and what R2 requires; path style
     * is what MinIO does out of the box and what an IP address must use, since
     * a bucket name cannot be prepended to one.
     */
    val pathStyle: Boolean = false,
    /** An [S3Preset] id, for the screen. Empty: the endpoint was typed. */
    val preset: String = "",
    /** The `{account}` part of the preset's endpoint. See [S3Account]. */
    val account: String = "",
)

/** An FTP server. */
data class FtpConfig(
    val host: String = "",
    val port: Int = 21,
    val user: String = "",
    /** In [SettingsBackup.SECRET_KEYS]. */
    val password: String = "",
    /** Directory to write into, relative to wherever the login lands. */
    val path: String = "",
    /**
     * Whether to negotiate TLS with `AUTH TLS` before logging in.
     *
     * On by default and worth leaving on: plain FTP sends the password as text
     * on the wire, exactly like WebDAV over http, which this app refuses
     * outright. FTP is allowed to be turned down to plain only because for some
     * old NAS boxes it is that or nothing, and the screen says what it costs.
     */
    val secure: Boolean = true,
)

/**
 * The backup that takes itself: the same bundle the Backup screen exports, put
 * where the user chose, on a schedule.
 *
 * Nothing here reaches a server of ours, which is why this exists in this shape
 * and not as an account. See [BackupDestination] for the places it can go.
 *
 * Grouped (see [CameraSettings] for why); DataStore keys stay flat.
 *
 * Inert until the chosen destination is actually usable. There is no default
 * destination, because every candidate is somewhere the user did not ask to
 * have their keyboard's contents put.
 */
data class AutoBackupSettings(

    /** Master switch. Does nothing on its own; a destination has to work too. */
    val enabled: Boolean = false,

    /** Which of the three destinations the backups go to. */
    val destination: BackupDestination = BackupDestination.FOLDER,

    /**
     * The WebDAV collection to write into, for example
     * `https://cloud.example.com/remote.php/dav/files/me/keyboard-backups`.
     *
     * Travels in an export: it is the user's choice and it means the same thing
     * on their next phone. [webDavPassword] does not.
     */
    val webDavUrl: String = "",

    val webDavUser: String = "",

    /**
     * Named in [SettingsBackup.SECRET_KEYS], so it stays out of exports and out
     * of device-protected storage. Server credentials, in the clear, for the
     * same reason [passphrase] is: an unattended upload has nobody to type them.
     */
    val webDavPassword: String = "",

    /** See [S3Config]. */
    val s3: S3Config = S3Config(),

    /** See [FtpConfig]. */
    val ftp: FtpConfig = FtpConfig(),

    /**
     * The Dropbox refresh token, or empty.
     *
     * A refresh token rather than an access token: the short-lived one expires
     * in four hours, and a backup that runs once a day would never have a live
     * one. In [SettingsBackup.SECRET_KEYS].
     */
    val dropboxRefreshToken: String = "",

    /** The OneDrive refresh token, same reasoning. In [SettingsBackup.SECRET_KEYS]. */
    val oneDriveRefreshToken: String = "",

    /**
     * A persisted tree URI, as a string, or empty.
     *
     * Not a path. What makes the folder writable is the grant attached to this
     * URI, and the grant can go away without the string changing, so every use
     * of it re-checks. Never travels in an exported bundle: see
     * [SettingsBackup.TRANSIENT_KEYS].
     */
    val folderUri: String = "",

    /** Wall-clock hours between runs. See [AutoBackupIntervals]. */
    val intervalHours: Int = 24,

    /** How many generations survive rotation. The newest is never one of them. */
    val keep: Int = 5,

    /**
     * Whether the job waits for an unmetered network.
     *
     * On, because the alternative is a daily upload to S3, WebDAV or Drive out
     * of somebody's mobile data with nothing anywhere saying so. Ignored for
     * [BackupDestination.FOLDER], which needs no network at all — see
     * [needsNetwork].
     */
    val requireUnmetered: Boolean = true,

    /**
     * Whether the job waits for the charger.
     *
     * On, which is what it always silently was. Worth being able to turn off:
     * a phone that is charged in the car and never overnight would otherwise
     * never reach the end of a period awake and plugged in, and get no backups
     * at all without ever reporting a failure.
     */
    val requireCharging: Boolean = true,

    /**
     * Which parts of the bundle go in, as [ConfigBackup.Section] ids.
     *
     * Also drives the manual export on the Backup screen, which until this
     * existed forgot the choice every time the screen closed.
     */
    val sections: Set<String> = DEFAULT_SECTIONS,

    /**
     * Whether API keys ride along. Off, and worth leaving off: a bundle with
     * this on is a file that has to be treated like a password.
     */
    val includeSecrets: Boolean = false,

    /** Whether the file is encrypted under [passphrase]. See [BackupCrypto]. */
    val encrypt: Boolean = false,

    /**
     * The passphrase, stored in the clear.
     *
     * It has to be: a backup that runs with nobody watching has nobody to type
     * it. So this protects the file where it lands — in a synced folder, in
     * somebody's copy of that folder — and not against a person holding an
     * unlocked device. The settings screen says exactly that.
     *
     * Named in [SettingsBackup.SECRET_KEYS], so it never reaches
     * device-protected storage and never leaves in an export by default.
     */
    val passphrase: String = "",

    /**
     * The per-install KDF salt, base64, made when the passphrase is first set.
     *
     * Only used to *write* new files; a file carries its own salt in its
     * header, so losing this never makes an existing backup unreadable.
     */
    val kdfSalt: String = "",

    /** When the last run finished, or 0. Never travels: another device's clock. */
    val lastRunAtMs: Long = 0,

    /**
     * The name of the [com.wasimaster.wmkeyboard.core.settings.sink.SinkError]
     * the last run stopped on, or empty.
     *
     * Kept because the failure this feature actually has is the silent one: a
     * grant dies, backups stop, and nothing anywhere says so until the phone
     * the backups were for is gone.
     */
    val lastError: String = "",

    /**
     * Every place backups go. Replaces the single [destination] and its
     * fields, which are still read once to seed this list (see
     * [BackupLocation.fromLegacy]) and are otherwise left alone.
     */
    val locations: List<BackupLocation> = emptyList(),

    /** How the last run went at each location, by [BackupLocation.id]. */
    val locationStatus: Map<String, LocationStatus> = emptyMap(),

    /**
     * What the manual "Export to a file" puts in, as section ids. Its own
     * list since the automatic backup and sync got theirs: the three are
     * different decisions, a one-off file to hand to someone included.
     */
    val exportSections: Set<String> = DEFAULT_SECTIONS,

    /** Keeping several devices the same. See [SyncSettings]. */
    val sync: SyncSettings = SyncSettings(),

    /**
     * Whether the automatic backup carries API keys. Its own switch, off,
     * rather than [includeSecrets], which belongs to the manual export: a key
     * someone chose to put in one file they handed over must not start
     * riding in every scheduled upload.
     */
    val backupIncludeSecrets: Boolean = false,
) {
    companion object {

        /**
         * The same split the Backup screen's switches defaulted to: the parts
         * that describe a set-up keyboard, without the two personal ones
         * (typed words, copied text) or the two bulky ones (sticker and icon
         * images). Turning those on is a decision the user makes in front of a
         * warning, not one made for them here.
         */
        val DEFAULT_SECTIONS: Set<String> = setOf(
            ConfigBackup.Section.SETTINGS.id,
            ConfigBackup.Section.THEMES.id,
            ConfigBackup.Section.SNIPPETS.id,
            ConfigBackup.Section.WORDLISTS.id,
            ConfigBackup.Section.ADDONS.id,
            ConfigBackup.Section.EMOJI.id,
            ConfigBackup.Section.STATISTICS.id,
            ConfigBackup.Section.VOCAB.id,
        )
    }
}

/** [AutoBackupSettings.sections] as the sections themselves. */
val AutoBackupSettings.sectionSet: Set<ConfigBackup.Section>
    get() = ConfigBackup.Section.entries.filterTo(LinkedHashSet()) { it.id in sections }

/** [AutoBackupSettings.exportSections] as the sections themselves. */
val AutoBackupSettings.exportSectionSet: Set<ConfigBackup.Section>
    get() = ConfigBackup.Section.entries.filterTo(LinkedHashSet()) { it.id in exportSections }

/** Locations that can be tried at all: enabled and configured. */
val AutoBackupSettings.activeLocations: List<BackupLocation>
    get() = locations.filter { it.active }

/** Where an automatic backup goes: every usable location ticked for backups. */
val AutoBackupSettings.backupTargets: List<BackupLocation>
    get() = locations.filter { it.active && it.backup }

/**
 * How sync runs: shortly after a change, on a timer, or only when asked.
 * Stored by [id]; never store an enum's name.
 */
enum class SyncMode(val id: String) { SOON("soon"), SCHEDULE("schedule"), MANUAL("manual") }

/**
 * Sync between devices.
 *
 * Off by default, like every trigger in this app. Everything here is per
 * install (see [SettingsBackup.TRANSIENT_KEYS]): a phone restored from a
 * bundle, or reached by another phone's sync file, does not start syncing
 * by itself.
 */
data class SyncSettings(
    val enabled: Boolean = false,
    val mode: SyncMode = SyncMode.SOON,
    /** Hours between runs for [SyncMode.SCHEDULE]. See [AutoBackupIntervals]. */
    val intervalHours: Int = 6,
    /**
     * The locations sync reads and writes, by [BackupLocation.id]. Any number:
     * devices only have to share one, and more copies mean sync still works
     * while one service is down. Not every location by default, because sync
     * files are small but frequent, and a folder on this phone is no use to
     * another one.
     */
    val locationIds: Set<String> = emptySet(),
    /** What syncs, as section ids. */
    val sections: Set<String> = DEFAULT_SECTIONS,
    /**
     * Whether API keys and passwords sync. Off by default, and the screen
     * warns before turning it on without a passphrase, but it is the user's
     * call: their keys, their storage.
     */
    val includeSecrets: Boolean = false,
    /**
     * Groups of settings this device keeps to itself, by
     * [com.wasimaster.wmkeyboard.core.settings.sync.SyncKeys.LocalGroup.id].
     * None by default: everything in Settings syncs until the user says a
     * part of it should differ here.
     */
    val keepLocal: Set<String> = emptySet(),
    val lastRunAtMs: Long = 0L,
    /** A `SinkError` name, a sync-specific reason, or empty. */
    val lastError: String = "",
) {
    companion object {
        /**
         * The set-up keyboard, without the personal parts. What the owner of
         * two phones most obviously wants to match, and nothing a person
         * might not expect to cross over.
         */
        val DEFAULT_SECTIONS: Set<String> = setOf(
            ConfigBackup.Section.SETTINGS.id,
            ConfigBackup.Section.THEMES.id,
            ConfigBackup.Section.SNIPPETS.id,
        )
    }
}

/** [SyncSettings.keepLocal] as the groups themselves. */
val SyncSettings.keepLocalGroups: Set<com.wasimaster.wmkeyboard.core.settings.sync.SyncKeys.LocalGroup>
    get() = com.wasimaster.wmkeyboard.core.settings.sync.SyncKeys.LocalGroup.of(keepLocal)

/** [SyncSettings.sections] as the sections themselves. */
val SyncSettings.sectionSet: Set<ConfigBackup.Section>
    get() = ConfigBackup.Section.entries.filterTo(LinkedHashSet()) { it.id in sections }

/** The locations sync reads and writes: the ticked ones that can be tried. */
fun SyncSettings.targets(locations: List<BackupLocation>): List<BackupLocation> =
    locations.filter { it.active && it.id in locationIds }

/**
 * What [KeyboardSettings.fontScale] can be set to, on the Accessibility screen
 * and per screen variant.
 *
 * The ceiling used to be 150%, which is not enough for severe low vision: key
 * height lives on another screen, so a label that still cannot be read has
 * nowhere left to go. Past 200% a label stops fitting its key on a phone, which
 * is a real limit rather than a chosen one.
 */
val KeyFontScaleRange = 0.7f..2.0f

/**
 * Every file under `filesDir` that holds something learned from typing, for
 * [SettingsRepository.clearLearnedData].
 *
 * A list rather than four literals at the call site because the set has grown
 * twice and both times a caller was missed: the language-mix signal survived
 * the Privacy screen's delete entirely, so a wipe left the keyboard still
 * guessing which language a user mixes.
 */
/** The hand model's file (see `KeyOffsets` in :core:prediction), under the learning directory. */
const val HAND_MODEL_FILE = "learning/key_offsets.json"

/** What the user's own fixes taught autocorrect (see `CorrectionMemory` in :core:prediction). */
const val LEARNED_CORRECTIONS_FILE = "learning/learned_corrections.json"

/** Where this hand lands when tapping: a second `KeyOffsets`, with its own file. */
const val TAP_MODEL_FILE = "learning/tap_offsets.json"

/** Which language the user writes in each app (see `AppLanguageMix` in :core:prediction). */
const val APP_LANGUAGE_MIX_FILE = "learning/app_language_mix.json"

/** Which script the user overruled a phonetic spelling into (see `PhoneticScriptChoices` in :core:prediction). */
const val PHONETIC_SCRIPT_CHOICES_FILE = "learning/phonetic_script_choices.json"

/** What the user did with the words their glides gave them (see `GlideOutcomes` in :core:prediction). */
const val GLIDE_OUTCOMES_FILE = "learning/glide_outcomes.json"

/** How the user draws each word (see `GlideShapeStore` in :core:prediction). */
const val GLIDE_SHAPES_FILE = "learning/glide_shapes.json"

/** How far up the sandbox ladder the user has climbed (see `GlideSandboxLadder` in :core:prediction). */
const val GLIDE_SANDBOX_FILE = "learning/glide_sandbox.json"

/**
 * Everything the "learn my swipe style" switch governs and its Forget deletes:
 * the stores a kept swipe teaches, apart from the word itself.
 */
val SWIPE_STYLE_FILES = listOf(HAND_MODEL_FILE, GLIDE_OUTCOMES_FILE, GLIDE_SHAPES_FILE)

val LEARNED_DATA_FILES = listOf(
    HAND_MODEL_FILE,
    LEARNED_CORRECTIONS_FILE,
    TAP_MODEL_FILE,
    APP_LANGUAGE_MIX_FILE,
    PHONETIC_SCRIPT_CHOICES_FILE,
    GLIDE_OUTCOMES_FILE,
    GLIDE_SHAPES_FILE,
    "learning/user_lexicon.json",
    "learning/pending_learn.json",
    "learning/emoji_usage.json",
    "learning/correction_stats.json",
    "learning/cjk_history.json",
    "learning/language_mix.json",
    "learning/word_ranks.json",
)

/**
 * The hour values [AutoBackupSettings.intervalHours] can be set to.
 *
 * A ladder rather than a plain 1..168 range: a slider that wide makes the value
 * almost everyone wants — once a day — a pixel-perfect drag. Every hour up to
 * six, then the round divisors of a day, then whole days out to a week. Wide
 * enough for the two-hourly and the fortnightly-quota cases the old fixed list
 * of four refused, without pretending anyone needs to pick 137.
 */
val AutoBackupIntervals = listOf(1, 2, 3, 4, 6, 8, 12, 18, 24, 36, 48, 72, 96, 120, 168)

/**
 * What [AutoBackupSettings.keep] can be set to.
 *
 * Ordinary integers, so a slider lands on each one. The ceiling is a rotation
 * that keeps a month of dailies; the floor keeps one previous generation, since
 * the newest is never counted.
 */
val AutoBackupKeepRange = 1..30

/**
 * Whether the chosen destination has everything it needs to be tried.
 *
 * Not whether it will work: a folder grant can be revoked and a password can be
 * wrong, and only the sink can find that out. This is the cheaper question of
 * whether there is any point asking, and it is what gates both the scheduler
 * and the switch on the screen.
 *
 * Google Drive needs nothing stored, because what it needs is an authorization
 * held by Play services rather than anything of ours.
 */
val AutoBackupSettings.destinationConfigured: Boolean
    get() = when (destination) {
        BackupDestination.FOLDER -> folderUri.isNotEmpty()
        BackupDestination.WEBDAV -> webDavUrl.isNotEmpty() && webDavUser.isNotEmpty()
        BackupDestination.DRIVE -> true
        BackupDestination.S3 ->
            s3.bucket.isNotEmpty() &&
                s3.accessKeyId.isNotEmpty() &&
                s3.secretAccessKey.isNotEmpty()
        // The token is the whole configuration: it is what the sign-in produced
        // and the only thing either service needs from us.
        BackupDestination.DROPBOX -> dropboxRefreshToken.isNotEmpty()
        BackupDestination.ONEDRIVE -> oneDriveRefreshToken.isNotEmpty()
        BackupDestination.FTP -> ftp.host.isNotEmpty() && ftp.user.isNotEmpty()
        // Only ever a location in the list. The single destination these
        // settings describe predates them and cannot name one.
        BackupDestination.SFTP, BackupDestination.SMB, BackupDestination.GIT, BackupDestination.IMAP -> false
    }

/**
 * Whether reaching this destination costs data.
 *
 * [BackupDestination.FOLDER] is the odd one: a `DocumentsProvider` is usually
 * local storage, so demanding a network for it would mean an offline phone
 * never backing up to its own SD card. It can be backed by a cloud provider's
 * app, but the app on the other side of that grant does its own syncing on its
 * own terms, and we cannot see which case we are in.
 */
val BackupDestination.needsNetwork: Boolean
    get() = this != BackupDestination.FOLDER

/**
 * Whether access is an account sign-in rather than a folder grant or typed
 * credentials. Decides the words for a lost permission: "choose the folder
 * again" is the wrong advice for Dropbox.
 */
val BackupDestination.signsIn: Boolean
    get() = this == BackupDestination.DRIVE ||
        this == BackupDestination.DROPBOX ||
        this == BackupDestination.ONEDRIVE

/**
 * AI-tool settings, grouped rather than flat because [KeyboardSettings] sits against
 * the JVM's 255-slot method-argument limit: Kotlin's generated `copy$default` takes
 * every field plus its mask ints, so a flat class stops loading once the count creeps
 * past ~245. Grouping a tool's own settings is the pattern [CameraSettings] and
 * [WhisperSettings] already follow.
 *
 * The DataStore keys are unchanged by the nesting (`ai_provider`, `ai_max_tokens`,
 * `hf_token`, …), so no existing preference is lost — only the Kotlin path moved. That
 * also means [SettingsBackup] and [LockedSettings], which work on the raw preference
 * map, need no change at all.
 */
data class AiSettings(
    // Provider, per-provider keys/models and self-hosted URLs.
    val provider: AiProvider = AiProvider.ANTHROPIC,
    val anthropicKey: String = "",
    val openAiKey: String = "",
    val geminiKey: String = "",
    val anthropicModel: String = "",
    val openAiModel: String = "",
    val geminiModel: String = "",
    val ollamaUrl: String = "",
    val ollamaModel: String = "",
    val lmStudioUrl: String = "",
    val lmStudioModel: String = "",
    val xaiKey: String = "",
    val xaiModel: String = "",
    val deepSeekKey: String = "",
    val deepSeekModel: String = "",
    /**
     * Address of any other OpenAI-compatible service, up to and including the
     * version segment: the client adds `/chat/completions`. The key is optional,
     * because a gateway on the user's own network often wants none.
     */
    val compatibleUrl: String = "",
    val compatibleKey: String = "",
    val compatibleModel: String = "",
    /**
     * Ceiling on the length of one response, in tokens. Reasoning models get a
     * multiple of it at request time (AiClient), because their think block
     * spends the same budget as the answer.
     *
     * `0` means "send no ceiling at all", so the service applies its own. That
     * is `AiClient.PROVIDER_MAXIMUM`, which cannot be named here: :core:settings
     * sits below the module that holds the client.
     */
    val maxTokens: Int = 8192,
    /**
     * Context window for an on-device model, in tokens, or `0` for the model's
     * own default. This is the whole window, prompt included, not a ceiling on
     * the response: the on-device engine has no per-response limit to set.
     * Changing it reloads the model.
     */
    val localContextTokens: Int = 0,
    /** Target language of the AI translate action. */
    val translateTo: String = "English",
    /**
     * The user's own actions, plus their edits of the shipped ones. An entry
     * whose id matches a shipped action shadows it; see `resolveAiActions`.
     */
    val customActions: List<AiActionSpec> = emptyList(),
    /** Ids in the order the panel draws them. Empty = the shipped order. */
    val actionOrder: List<String> = emptyList(),
    /** Ids the user turned off. A shipped action is hidden, never deleted. */
    val hiddenActions: List<String> = emptyList(),
    /**
     * Selected on-device model: a LocalLlmCatalog id, or "custom:<fileName>"
     * for an imported file. Blank = none selected.
     */
    val localModelId: String = "",
    val localBackend: LocalLlmBackend = LocalLlmBackend.CPU,
    /** Hugging Face access token — only needed to download gated models (Gemma). */
    val hfToken: String = "",
    /**
     * Show reasoning models' <think> passages verbatim while they stream.
     * Off (default) hides them behind a "reasoning" progress bar and strips
     * them from the result.
     */
    val showThinking: Boolean = false,
    /** Show a model/provider switcher row on the AI panel itself. */
    val panelModelPicker: Boolean = true,
    /**
     * Offer a "Changes" view of a result, marking what the model added and
     * deleted against the text it ran on. On by default: it costs one chip and
     * no work at all until the user presses it.
     */
    val diffView: Boolean = true,
    /**
     * Open a finished result on the changes rather than the plain text. Off, so
     * the panel behaves the way it always did until the user asks otherwise.
     */
    val diffOpensFirst: Boolean = false,
    /**
     * Keep a record of what the AI tool was asked and what it answered.
     *
     * Off, and it stays off unless the user turns it on: the records are their
     * own writing. Nothing is kept from a password field or in incognito even
     * when this is on, and turning it off deletes what was stored.
     */
    val historyEnabled: Boolean = false,
    /** How many runs the history keeps before the oldest fall off. */
    val historyMax: Int = 100,
    /**
     * Keep chat conversations between sessions.
     *
     * On, which is what the store always did. Worth being able to turn off:
     * transcripts persisted with no switch and no bulk delete, while the less
     * sensitive one-shot AI history had both — the wrong way round, since a
     * conversation is the longer and more revealing record of the two.
     */
    val keepChats: Boolean = true,
    /**
     * Enter sends the message in the chat on the keyboard's AI panel (#280).
     *
     * Off: Enter adds a line and only the Send button sends, which is what a
     * prompt of more than one line needs and what messaging apps do.
     */
    val chatEnterSends: Boolean = false,
    /**
     * The AI panel was last left in its chat mode, so it opens there again.
     * Remembered for the user, not set by them: there is no row for it, the
     * switch on the panel's header is the control.
     */
    val panelChat: Boolean = false,
    /**
     * How much text before the cursor a "carry this on" action sends, in
     * characters.
     *
     * Was 4,000, hard-coded and silent: a long-form writer's Continue lost the
     * earlier context with nothing to say so. Bigger costs tokens and latency
     * on every run, which is why it is a number and not simply raised.
     */
    val beforeCursorChars: Int = 4_000,
)

/**
 * Caps and pools the tools used to hard-code, grouped for the ceiling reason on
 * [KeyboardSettings.photoBackground]. The flat list is three or four fields from
 * the JVM limit on `copy$default`, so this domain takes one slot once and
 * further tool limits cost nothing.
 */
data class ToolLimitSettings(
    /**
     * How long a weather reading is reused before the tool fetches again, in
     * minutes. Was 15, hard-coded, while the currency tool exposed its own
     * cache — so someone watching a storm could not ask for fresher numbers.
     */
    val weatherRefreshMinutes: Int = 15,
    /**
     * How many outgoing links the Wikipedia tool lists. Was capped at 200 while
     * the API allows 500, and a longer article's Links tab simply stopped with
     * nothing to say it had.
     */
    val wikiLinkLimit: Int = 200,
    /**
     * Longest text the QR tool will encode. Past this the preview refuses, and
     * 2,000 was fixed: a longer payload is possible at a lower error-correction
     * level, which is a trade the user should get to make.
     */
    val qrMaxChars: Int = QrCodeGen.MAX_CHARS,
    /**
     * The symbol pool the password generator draws from. Blank means the built-in
     * set. Sites that reject particular punctuation forced people to regenerate
     * repeatedly instead of narrowing it once.
     */
    val passwordSymbols: String = "",
)

/** How the app-launcher grid orders its apps. */
enum class AppSortOrder { ALPHABETICAL, RECENT_FIRST }

/**
 * The outline the app-launcher panel cuts each icon to. [SYSTEM] draws the
 * icon as the device renders it: an adaptive icon already carries the
 * launcher mask of the phone, so a second cut on top only rounds it again.
 */
enum class LauncherIconShape { CIRCLE, ROUNDED, SYSTEM }

/**
 * Where a tap in the app-launcher panel opens the app. [FLOATING] asks for a
 * centred window and lands full screen on a phone without a freeform or
 * desktop mode; [SPLIT] puts the app beside the one being typed in and needs
 * Android 12L (API 32) or later, where the system split screen accepts an
 * adjacent launch from a keyboard.
 */
enum class LauncherOpenMode { NORMAL, FLOATING, SPLIT }

/**
 * Two apps the launcher opens side by side: [first] on top (or left), then
 * [second] beside it. [name] is optional; the panel falls back to both labels.
 */
data class LauncherSplitCombo(
    val first: String,
    val second: String,
    val name: String = "",
) {
    companion object {
        /**
         * One combo per line, `first<TAB>second<TAB>name`. Tabs and line breaks
         * in a name become spaces on the way in, so a name can never break the
         * record it sits in.
         */
        fun encode(combos: List<LauncherSplitCombo>): String =
            combos.joinToString("\n") { combo ->
                val name = combo.name
                    .replace('\t', ' ')
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .trim()
                listOf(combo.first, combo.second, name).joinToString("\t")
            }

        /** Skips malformed lines rather than failing the whole list. */
        fun decode(raw: String?): List<LauncherSplitCombo> =
            raw.orEmpty().split('\n').mapNotNull { line ->
                val parts = line.split('\t')
                val first = parts.getOrNull(0)?.trim().orEmpty()
                val second = parts.getOrNull(1)?.trim().orEmpty()
                if (first.isEmpty() || second.isEmpty()) return@mapNotNull null
                LauncherSplitCombo(first, second, parts.getOrNull(2)?.trim().orEmpty())
            }

        /**
         * [combos] with [combo] appended, unless the same pair in the same
         * order is already there: a second copy would only be a second chip.
         */
        fun add(
            combos: List<LauncherSplitCombo>,
            combo: LauncherSplitCombo,
        ): List<LauncherSplitCombo> =
            if (combos.any { it.first == combo.first && it.second == combo.second }) {
                combos
            } else {
                combos + combo
            }

        /** [combos] with the entry at [from] moved to [to]; out of range is a no-op. */
        fun move(combos: List<LauncherSplitCombo>, from: Int, to: Int): List<LauncherSplitCombo> {
            if (from !in combos.indices || to !in combos.indices || from == to) return combos
            val next = combos.toMutableList()
            next.add(to, next.removeAt(from))
            return next
        }
    }
}

/**
 * App-launcher tool settings, grouped like [AiSettings] (same 255-slot
 * rationale). The keys stay flat (`launcher_*`), so backup and locked-settings
 * handling need no change.
 */
data class LauncherToolSettings(
    val sortOrder: AppSortOrder = AppSortOrder.ALPHABETICAL,
    /** App names under the grid icons; off leaves bare icons. */
    val showLabels: Boolean = true,
    /** Track launches and lead the grid with a recents row. */
    val recentsEnabled: Boolean = true,
    /** Long-press an app to open its activity list. */
    val activityDrilldown: Boolean = true,
    /**
     * List activities other apps cannot start, dimmed. Off by default: they
     * fail with SecurityException when tapped, so they are debugging fare.
     */
    val showNonExported: Boolean = false,
    /** Pinned packages, in the user's order; they lead the grid. */
    val pinned: List<String> = emptyList(),
    /** Most-recent-first launched packages, capped at [maxRecents]. */
    val recents: List<String> = emptyList(),
    /**
     * How many recent apps the row keeps.
     *
     * Ten, hard-coded, against a grid three to six wide — so it never filled
     * clean rows at any width, and neither someone who app-hops nor someone
     * who wants the row out of the way could say so.
     */
    val maxRecents: Int = MAX_RECENTS,
    /**
     * Apps per grid row; [AUTO_COLUMNS] fits as many 68 dp cells as the width
     * holds. A fixed count lets a tablet keep big targets or a phone pack more
     * apps in (#198).
     */
    val gridColumns: Int = AUTO_COLUMNS,
    /** Grid icon diameter in dp; labels and cell height follow it. */
    val iconSizeDp: Int = ICON_SIZE_DP,
    val iconShape: LauncherIconShape = LauncherIconShape.CIRCLE,
    /**
     * Packages left out of the grid and the pinned/recent row. Search still
     * finds them, which is also the way back: its page, opened from the
     * results, shows it again.
     */
    val hidden: List<String> = emptyList(),
    /** What a plain tap on an app does; the hold menu offers all three. */
    val openMode: LauncherOpenMode = LauncherOpenMode.NORMAL,
    /** User-made split-screen pairs, in the user's order. */
    val combos: List<LauncherSplitCombo> = emptyList(),
) {
    companion object {
        const val MAX_RECENTS = 10
        val RECENTS_RANGE = 4..20
        const val AUTO_COLUMNS = 0
        val COLUMNS_RANGE = 3..8
        const val ICON_SIZE_DP = 42
        val ICON_SIZE_RANGE = 30..60
    }
}

/**
 * Camera-tool settings, grouped into their own object.
 *
 * Kotlin generates a `copy$default` for a data class that takes every property
 * as an argument plus bookkeeping slots, and a JVM method descriptor is capped
 * at 255 argument slots. [KeyboardSettings] had grown to that ceiling, so
 * cohesive families like this one are split off to keep it loadable — the
 * DataStore keys stay flat, so this is purely an in-memory grouping.
 */
/**
 * Chinese and Cantonese conversion-IME options, grouped rather than flat.
 *
 * [KeyboardSettings] sits at the JVM's `copy$default` argument ceiling — the same
 * reason [CameraSettings] and [LongPressLetterActions] were split out — so a
 * cohesive family like this one lives in its own class. Folding the two existing
 * pinyin options in here alongside the new one leaves the parent with fewer
 * fields than before, not more.
 *
 * The DataStore keys are unchanged by the nesting (`pinyin_fuzzy`,
 * `pinyin_double_pinyin`), so no existing preference is lost — only the Kotlin
 * path moved.
 */
/**
 * Password-generator defaults, grouped rather than flat because [KeyboardSettings]
 * sits against the JVM's 255-slot method-argument limit: Kotlin's generated
 * `copy$default` takes every field plus its mask ints, so a flat class stops
 * loading once the count creeps past ~245. Grouping a tool's own settings is the
 * pattern the other sub-classes here already follow.
 */
data class PasswordGeneratorSettings(
    val pwLength: Int = 16,
    val pwUppercase: Boolean = true,
    val pwDigits: Boolean = true,
    val pwSymbols: Boolean = true,
    /** Skip look-alikes (Il1O0…) for passwords read aloud or retyped. */
    val pwExcludeAmbiguous: Boolean = false,
    /** Generator opens in passphrase mode instead of password mode. */
    val pwPassphraseMode: Boolean = false,
    val ppWordCount: Int = 4,
    val ppSeparator: String = "-",
    val ppCapitalize: Boolean = false,
    val ppIncludeDigit: Boolean = false,
)

/**
 * The typing-speed test's settings and records, grouped for the same
 * ceiling reason as [PasswordGeneratorSettings]. The panel edits the
 * options live, so they double as the tool's own settings and as the memory
 * of how the user last left it. DataStore keys stay flat (`tt_*`).
 */
data class TypingTestSettings(
    val mode: TypingTestMode = TypingTestMode.TIME,
    val duration: Int = 30,
    val wordCount: Int = 25,
    val punctuation: Boolean = false,
    val numbers: Boolean = false,
    /**
     * Let a run be typed with glide gestures. Off, a swipe over the keys
     * does nothing during a test — the score is for tapping alone.
     */
    val glide: Boolean = false,
    /**
     * Show word suggestions during a run, and let a tap on one finish the
     * word. Off, the run is scored on keystrokes alone.
     */
    val suggestions: Boolean = false,
    /** Personal bests per config, encoded by [TypingBests]. */
    val bests: String = "",
    /** Recent WPM scores, oldest first, encoded by [TypingHistory]. */
    val history: String = "",
    val completed: Int = 0,
    /** Unlocked achievement badges, encoded by [TypingAchievements]. */
    val achievements: String = "",
)

/**
 * Where the currency tool gets its numbers. Grouped for the same reason as
 * [PasswordGeneratorSettings]: [KeyboardSettings] is close to the argument
 * ceiling, and these six belong to one feature.
 *
 * Both provider lists are ordered chains — the first entry is the source
 * that is tried, the rest are fallbacks — so "also use the others" is a
 * one-or-many list rather than a second setting. Ids are
 * `CurrencyClient.Provider` names.
 */
/**
 * The contextual chip families — text that is not a sum or a keyword but
 * still sounds like a job a tool does. All under the same master switch as
 * the other smart chips ([KeyboardSettings.smartSuggestions]).
 */
data class SmartChipSettings(
    /** "next friday" → the date it lands on, and the calendar opened there. */
    val dates: Boolean = true,
    /** "will it rain" → the forecast on the strip. */
    val weather: Boolean = true,
    /** "define X" / "who is X" → the dictionary or Wikipedia lookup. */
    val lookups: Boolean = true,
    /** "how do you say" / "in spanish" → a translator hint. */
    val intents: Boolean = true,
    /** "happy birthday" → a GIF search. */
    val gifs: Boolean = true,
    /** "1234567" → the same digits grouped, "1,234,567". */
    val numbers: Boolean = true,
    /** Which grouping the number chip offers; Auto follows the typed language. */
    val numberGrouping: NumberGrouping = NumberGrouping.AUTO,
)

/**
 * Where the selection macros draw.
 *
 * [OWN_ROW] gives them a row of their own ([BarRow.MACROS]) that arrives with
 * the selection and leaves with it, so nothing the strip was showing is taken
 * away. [STRIP] puts them on the suggestion strip instead, which costs no
 * height at all: while there is a selection there is nothing being typed, so
 * the word candidates the strip would draw are stale anyway.
 */
enum class SelectionMacroPlacement { OWN_ROW, STRIP }

/**
 * Selection macros (see `SelectionMacros`): read the selection, offer the
 * handful of actions that shape of text is for.
 *
 * Off by default. It is a bar that appears out of a gesture people already
 * make for other reasons, so somebody who does not want it must never meet it,
 * and somebody who does turns on one switch.
 */
data class SelectionMacroSettings(
    /** The whole feature. Nothing below this is read while it is off. */
    val enabled: Boolean = false,
    /** A row of their own, or the suggestion strip. */
    val placement: SelectionMacroPlacement = SelectionMacroPlacement.OWN_ROW,
    /**
     * The macros that may be offered, out of [SelectionMacros.configurable].
     * A macro missing from the set is never drawn, whatever is selected.
     */
    val macros: Set<SelectionMacro> = SelectionMacros.defaultMacros,
    /**
     * The row's order, over every bar-capable macro. Filtered by [macros] and
     * by what the selection allows at offer time; Undo is pinned first
     * whatever this says.
     */
    val order: List<SelectionMacro> = SelectionMacros.defaultOrder,
    /**
     * AI action ids drawn as direct buttons after the AI chip. Empty as
     * shipped: the AI chip alone is on, and these are the user's picks.
     */
    val aiDirectActions: List<String> = emptyList(),
    /**
     * Zone ids for the Zones ladder. Empty means UTC plus the device's zone,
     * resolved by [effectiveTimeZones] so the default follows the device.
     */
    val timeZones: List<String> = emptyList(),
    /**
     * Read a selected phone number, address or link as that thing, rather than
     * treating every selection as plain text.
     *
     * Off, the bar still offers the generic actions (copy, share, the case
     * ladder) and never the entity ones. Worth having for anyone who finds the
     * detection guesses wrong more often than it guesses right, since the
     * generic half of the bar is the half that always applies.
     */
    val detectEntities: Boolean = true,
)

/** The zones the ladder shows: the picked ones, or UTC and the device's own. */
fun SelectionMacroSettings.effectiveTimeZones(deviceZoneId: String): List<String> =
    timeZones.ifEmpty { listOf("UTC", deviceZoneId).distinct() }

data class RateSourceSettings(
    /** Best first. The default depends on the channel; see [CurrencyClient.Provider.fiatDefaults]. */
    val fiatProviders: List<String> = CurrencyClient.Provider.fiatDefaults(),
    /** Read coin amounts ("1 btc") and show coins in the converter. */
    val cryptoEnabled: Boolean = true,
    val cryptoProviders: List<String> = CurrencyClient.Provider.cryptoDefaults(),
    /** Coin prices move by the minute, unlike the daily fiat table. */
    val cryptoCacheMinutes: Int = 5,
    /** The coins that are on; empty means the catalogue's own default set. */
    val cryptoTickers: Set<String> = emptySet(),
    /** Decimal places on coin amounts, or 0 to keep significant digits instead. */
    val cryptoDecimals: Int = 0,
    /**
     * Fetch rates the moment a currency chip needs them. Off, the chip waits
     * for a tap first, so typing an amount never reaches the network by
     * itself. The panel fetches on open either way, and cached rates convert
     * with no fetch at all.
     *
     * Off on F-Droid, for the same reason as
     * [KeyboardSettings.autoDownloadLanguageData]: users of that build expect
     * nothing to go online until they ask.
     */
    val autoFetch: Boolean = !BuildConfig.ENABLE_FDROID,
)

data class CjkSettings(
    /** Chinese: treat confusable pinyin initials/finals as equivalent (zh↔z, an↔ang…). */
    val pinyinFuzzy: Boolean = false,
    /**
     * Which of [PinyinFuzzy.PAIRS] [pinyinFuzzy] applies, by pair id. All of
     * them by default, which is what the switch meant on its own.
     *
     * The eleven groups were all-or-nothing, and they are not one preference:
     * the nasal endings are a regional accent, while n↔l costs precision on
     * every syllable starting with either. Sogou and Google Pinyin both let a
     * user take one without the other.
     */
    val pinyinFuzzyPairs: Set<String> = PinyinFuzzy.ALL_PAIRS,
    /** Chinese: the Double Pinyin scheme, or OFF for full pinyin. */
    val pinyinDoublePinyin: DoublePinyinScheme = DoublePinyinScheme.OFF,
    /** Convert candidate output to Traditional characters (Taiwan, Hong Kong). */
    val traditionalOutput: Boolean = false,
    /**
     * Cantonese: match lazy-pronunciation mergers (n↔l, ng↔∅, -ng↔-n, -k↔-t).
     * On by default, because the mergers are how most speakers actually say the
     * words: someone who says 你 as lei5 types `lei` and, without this, gets
     * nothing back from a dictionary that files it under nei5.
     */
    val jyutpingLazy: Boolean = true,
    /**
     * Japanese: on the flick pad and the JIS kana layout, read a plain kana as
     * its small, dakuten or handakuten form too, so かつこう finds 学校 (#293).
     * On by default, as it is in Google Japanese Input: every mark there is an
     * extra key, an exactly-typed reading still wins a near tie, and romaji
     * typing never sees it.
     */
    val kanaLooseMarks: Boolean = true,
    /**
     * The languages whose space bar types the ideographic space U+3000 (the
     * full-width 　) instead of an ASCII one, by language id (#341). Only a
     * press with nothing composing reaches it: a space mid-reading converts,
     * as it always has.
     *
     * Per language rather than one switch, because Japanese and Chinese
     * conventions differ (Japanese IMEs type a full-width space by default,
     * Chinese ones a half-width one) and a person typing both may want each
     * its own way. Empty by default, so nobody's space changes under them.
     */
    val fullWidthSpaceLanguages: Set<String> = emptySet(),
    /** Which region's vocabulary Traditional output should prefer. */
    val hanRegion: HanVariant.HanRegion = HanVariant.HanRegion.GENERIC,
)

/**
 * Where the camera tool's Search button sends a photo (#349). Stored by name.
 */
enum class PhotoSearchTarget {
    /**
     * The Google app's Lens, when it is installed; the Android share sheet
     * when it is not, so the button never dead-ends on a phone without it.
     */
    LENS,

    /** Upload to the [PhotoSearchEngine] and open its results in the browser. */
    WEB,

    /** The Android share sheet: any app that takes an image. */
    SHARE,
}

/**
 * The reverse image search site for [PhotoSearchTarget.WEB]. Stored by name.
 * None of them has a documented upload API; each is the request the site's
 * own upload button makes, so any one of them can break without notice.
 */
enum class PhotoSearchEngine {
    GOOGLE_LENS,
    BING,
    YANDEX,
    TINEYE,

    /** The user's own server: [CameraSettings.searchCustomUrl]. */
    CUSTOM,
}

data class CameraSettings(
    /** Camera tool opens on the selfie camera. */
    val preferFront: Boolean = false,
    /**
     * Self-timer the camera tool opens on, in seconds. 0 is no timer.
     *
     * Panel-local state reset to 0 on every open, so somebody who always uses
     * three seconds picked it again every time.
     */
    val timerSeconds: Int = 0,
    /**
     * Longest edge of a capture, in pixels.
     *
     * 1600 for everyone: too little for a photo of a document somebody
     * needs to read back, and more than a data-saver wants to send.
     */
    val captureMaxPx: Int = 1600,
    /** Mirror selfie captures so the photo matches the preview. */
    val mirrorFront: Boolean = true,
    /** Play a shutter click when the camera tool takes a photo. */
    val shutterSound: Boolean = true,
    /** Vibrate on camera controls, countdown ticks and the shutter. */
    val haptics: Boolean = true,
    /** Copy camera captures into Pictures/WM Keyboard as well as sending them. */
    val saveToGallery: Boolean = false,
    /**
     * Send the whole 4:3 frame. The keyboard usually crops the capture to the
     * part of the viewfinder that was on screen, so what you see is what you
     * send; this keeps the slivers that ran off the top and the bottom too.
     */
    val fullFrame: Boolean = false,
    /**
     * A Search button beside Retake and Send on the confirm step (#349). Off
     * by default: most photos taken here are for sending. The image search
     * tool's own camera button shows it whatever this says.
     */
    val searchButton: Boolean = false,
    /** Where Search sends the photo. */
    val searchWith: PhotoSearchTarget = PhotoSearchTarget.LENS,
    /** The site [PhotoSearchTarget.WEB] uploads to. */
    val searchEngine: PhotoSearchEngine = PhotoSearchEngine.GOOGLE_LENS,
    /**
     * Upload address for [PhotoSearchEngine.CUSTOM]. The keyboard posts the
     * photo there as multipart form data and opens the page the server
     * answers with: a redirect, a bare URL, or JSON with a `url` field.
     */
    val searchCustomUrl: String = "",
    /** Form field the photo goes in, for [PhotoSearchEngine.CUSTOM]. */
    val searchCustomField: String = "image",
)

/**
 * Which surface the voice tool opens, and where the collapsed bar sits,
 * grouped into their own object (see [CameraSettings] for why the top-level
 * class can't take more flat fields). DataStore keys stay flat.
 *
 * [active] is persisted state, not preference: the collapsed bar keeps the
 * keyboard's place on every new field until the user restores the keyboard,
 * and that has to survive the IME process being killed between fields.
 */
/** What [VoiceBarSettings.holdToTalkMs] may be set to. */
val HoldToTalkRange = 200..1500

data class VoiceBarSettings(
    /** What the voice tool opens: the full panel, the strip over the keys, or the collapsed bar. */
    val mode: String = MODE_PANEL,
    /**
     * How voice typing shares the field with the keys: [TYPING_BLOCK],
     * [TYPING_INTERACTIVE] or [TYPING_PLAIN]. See the constants.
     */
    val typingMode: String = TYPING_BLOCK,
    /** The collapsed bar stands in for the keyboard until the keyboard is restored. */
    val active: Boolean = false,
    /** The bar stands upright against a screen edge instead of lying along the bottom. */
    val vertical: Boolean = false,
    /** Where the horizontal bar rests: [SNAP_LEFT], [SNAP_CENTER] or [SNAP_RIGHT]. */
    val snap: Int = SNAP_CENTER,
    /** The vertical bar docks on the right screen edge (false = left). */
    val rightEdge: Boolean = true,
    /** The vertical bar's position along its edge, as a fraction of the travel. */
    val yBias: Float = 0.5f,
    /** The horizontal bar's height on screen: 1 = docked at the bottom, 0 = the top. */
    val dockBias: Float = 1f,
    /**
     * How long the mic must be held before dictation switches from tap-to-toggle
     * to press-and-hold, in milliseconds.
     *
     * 600 ms, hard-coded. It decides which of two quite different behaviours a
     * press gets, so a slow or tremor-affected tap landed on the wrong one every
     * time with nothing to adjust.
     */
    val holdToTalkMs: Int = 600,
    /**
     * A press and hold on the Voice tool while it is pinned to the toolbar
     * opens a menu of the three [typingMode]s, and picking one starts
     * dictation in it at once (#173). On by default; off gives the hold back
     * to the toolbar's own hold gesture (a bound tool, else the settings
     * page). The trackpad's `holdToOpen` shape: the toolbar's hold map can
     * only name another tool, so a hold that opens a menu is its own flag.
     */
    val holdPicksTypingMode: Boolean = true,
    /**
     * The surface the bar's expand button goes back to — whichever of
     * [MODE_PANEL] or [MODE_STRIP] the user collapsed from, defaulting to the
     * panel when the bar was chosen in settings instead.
     */
    val returnMode: String = MODE_PANEL,
    /**
     * The bar was entered through a collapse button rather than picked in
     * settings. Decides its exit control: an inline visit shows the expand
     * button (back to [returnMode]); a settings choice shows the keyboard
     * button (keys back, bar stays the default).
     */
    val inline: Boolean = false,
) {
    companion object {
        const val MODE_PANEL = "panel"
        const val MODE_STRIP = "strip"
        const val MODE_BAR = "bar"
        const val SNAP_LEFT = 0
        const val SNAP_CENTER = 1
        const val SNAP_RIGHT = 2

        /**
         * One block of speech at a time, the way voice typing has always
         * worked here: the words being recognised sit in the field as
         * composing text, and the first key press ends the session, because
         * a cumulative partial result cannot survive an edit inside it.
         */
        const val TYPING_BLOCK = "block"

        /**
         * The microphone stays open while you type. Nothing is composed in
         * the field: each phrase lands as finished text when you pause, so
         * the keys, the layouts and the suggestion strip all keep working
         * through the whole session.
         */
        const val TYPING_INTERACTIVE = "interactive"

        /**
         * [TYPING_INTERACTIVE] with every text rule turned off: no spoken
         * punctuation, no recognizer punctuation or capital letters, and no
         * spaces added around what lands. For code, terminals and any field
         * where you want exactly the words you said.
         */
        const val TYPING_PLAIN = "plain"
    }
}

/** The microphone survives typing: [VoiceBarSettings.TYPING_INTERACTIVE] or [VoiceBarSettings.TYPING_PLAIN]. */
fun VoiceBarSettings.interactiveTyping(): Boolean =
    typingMode == VoiceBarSettings.TYPING_INTERACTIVE || typingMode == VoiceBarSettings.TYPING_PLAIN

/** Dictated text lands exactly as it was recognised ([VoiceBarSettings.TYPING_PLAIN]). */
fun VoiceBarSettings.plainTyping(): Boolean = typingMode == VoiceBarSettings.TYPING_PLAIN

/**
 * Offline Whisper dictation settings, grouped into their own object (see
 * [CameraSettings] for why the top-level class can't take more flat fields).
 * DataStore keys stay flat.
 */
data class WhisperSettings(
    /**
     * Dictation backend: "system" = OS SpeechRecognizer, "whisper" = offline
     * LiteRT, "server" = a transcription server the user runs (#286).
     */
    val engine: String = "system",
    /**
     * The fallback Whisper catalog id — the model used for any language without
     * an entry in [modelByLang]. Blank falls back to the best downloaded model
     * for the language being typed in.
     */
    val modelId: String = "",
    /**
     * Language id → Whisper catalog id, for languages the user has pinned to a
     * specific model. Dictation resolves the model from the language of the
     * active layout, so a German-only graph can be the German choice while
     * everything else stays on a multilingual one.
     */
    val modelByLang: Map<String, String> = emptyMap(),
    /** Force Whisper to translate speech to English instead of transcribing verbatim. */
    val translate: Boolean = false,
    /**
     * The transcription server's API root for the "server" engine, e.g.
     * `http://192.168.1.10:8000/v1`. Any server that speaks OpenAI's
     * `/audio/transcriptions` works; see `TranscriptionClient.endpoint`.
     */
    val serverUrl: String = "",
    /** Bearer token for the server; blank sends no Authorization header. */
    val serverKey: String = "",
    /** The `model` field; blank leaves it out so the server uses its default. */
    val serverModel: String = "",
    /**
     * Send the active layout's language with each clip. Off lets the server
     * detect it, which suits people who dictate two languages on one layout.
     */
    val serverSendLanguage: Boolean = true,
    /**
     * Words dictation should listen for, handed to the system recognizer and
     * the transcription server with each phrase (#305): the words the user
     * added by hand, the ones the keyboard learned that no word list has
     * (names, jargon), and Android's personal dictionary. Offline Whisper
     * cannot take a hint and ignores it.
     */
    val biasPersonalWords: Boolean = true,
    /**
     * More words to listen for, typed in by the user and separated by commas.
     * Sent ahead of the learned ones, so they are the last to be cut when a
     * server has a limit.
     */
    val biasWords: String = "",
    /**
     * Text sent as the transcription server's `prompt`. Whisper models read it
     * as the text that came before the clip, so it steers spelling and style.
     * They keep only its last 224 tokens; newer models read far more. The
     * words above are sent in front of it.
     */
    val serverPrompt: String = "",
)

/**
 * What one step of a sideways backspace swipe takes off (issue #36).
 *
 * [WORD] is the long-standing behavior; [CHARACTER] is the finer gesture
 * HeliBoard and FUTO offer, where the swipe walks the caret back a letter at
 * a time and a full word costs a longer pull.
 */
enum class BackspaceSwipeUnit { WORD, CHARACTER }

/**
 * Text-editing tool and selection-editing settings, grouped into their own
 * object (see [CameraSettings] for why). DataStore keys stay flat.
 */
data class TextEditingSettings(
    /**
     * Auto-repeat interval while holding an arrow/backspace in the text-editing
     * tool, and while holding one of the toolbar's own cursor tools (see
     * [cursorToolsRepeatOnHold]).
     */
    val repeatMs: Int = 60,
    /**
     * Holding one of the toolbar's cursor tools repeats the move for as long as
     * the finger stays down, at [repeatMs].
     *
     * Off, a hold on the toolbar does what a hold on any other tool does and
     * opens that tool's settings page. On, it repeats, and that page is reached
     * from the Tools screen (or from a toolbox hold, unless the tool is also in
     * [toolboxRepeatTools]). Dragging to reorder survives either way: a hold
     * that travels still picks the tool up.
     */
    val cursorToolsRepeatOnHold: Boolean = true,
    /**
     * The cursor tools that repeat on a hold in the *toolbox* as well, named
     * one at a time rather than all together.
     *
     * Per tool because the toolbox is where a hold reaches every tool's own
     * settings page, and a tool in this set gives that up: its page is then
     * only on the Tools screen. That is a fine trade for the one or two moves
     * someone actually holds, and a bad one across a grid of forty tools, so
     * it is opted into a tool at a time. Empty by default for the same reason.
     *
     * Only [HoldRepeatCursorTools] may appear here; anything else is ignored.
     * Independent of [cursorToolsRepeatOnHold], which is the toolbar's own
     * switch: the two surfaces are held in different places for different
     * reasons, and neither is a master for the other.
     */
    val toolboxRepeatTools: Set<ToolbarTool> = emptySet(),
    /**
     * A press and hold on the toolbar's Selection mode tool turns selection mode
     * on for as long as the finger stays down, and the release turns it off.
     *
     * On, that hold is spoken for, so the tool's own settings page is reached
     * from the Tools screen or from a toolbox hold instead. That is the same
     * trade [cursorToolsRepeatOnHold] makes, and it is a setting for the same
     * reason: someone who only ever taps the tool would rather have the hold
     * back. Dragging to reorder survives either way, since a hold that travels
     * still picks the tool up.
     */
    val selectionModeHold: Boolean = true,
    /**
     * Two quick presses of the Selection mode tool select the word at the
     * cursor, and three select the line.
     *
     * Off, every press is a plain toggle. Worth turning off for anyone who
     * switches the mode on and straight back off faster than the double-tap
     * window, who would otherwise select a word they did not ask for.
     */
    val selectionModeMultiTap: Boolean = true,
    /**
     * Typing a bracket, brace or quote with text selected wraps the selection
     * in the pair (foo → (foo)) instead of replacing it.
     */
    val wrapSelectionWithPair: Boolean = true,
    /**
     * Typing an opening bracket with nothing selected types its closer too and
     * leaves the caret between the two, so the next character lands inside the
     * pair — "(" gives "(|)". Pressing the closer while it is the character in
     * front of the caret steps over it instead of typing a second one, and one
     * backspace on an empty pair takes both halves out.
     *
     * Off by default, because it changes what a bracket key types. Brackets
     * only: quotes are left alone, since the apostrophe in "don't" is the same
     * key and closing it would be wrong far more often than right. `<` is left
     * alone too — outside code it is a less-than sign, and "5 <" is not a
     * bracket anybody is opening.
     */
    val autoCloseBrackets: Boolean = false,
    /**
     * Pressing shift with text selected cycles its case (lower → Title → UPPER)
     * instead of arming shift for the next character.
     */
    val recapitalizeSelectionWithShift: Boolean = true,
    /**
     * How long after a space another space still counts as a double space, for
     * the ". " and tab rules.
     *
     * 400 ms is the long-standing constant. It is a setting because both ends
     * of it fail for someone: a slow or tremor-affected typist never lands two
     * spaces inside the window and never sees a full stop at all, and a very
     * fast one gets full stops they did not ask for between words.
     */
    val doubleSpaceWindowMs: Int = 400,
    /**
     * How far the finger travels along the spacebar per character, when a
     * spacebar swipe is set to cursor control.
     *
     * Smaller moves the caret faster. 16 dp is the long-standing constant; it
     * is short enough that a wide screen runs out of spacebar before the caret
     * reaches the end of a long line, and long enough that a shaky hand
     * overshoots.
     */
    val spaceCursorStepDp: Int = 16,
    /**
     * A magnifier over the caret while a spacebar cursor swipe moves it
     * (discussion #303): the line around the caret, enlarged, in a bubble over
     * the text. The trackpad has its own switch, [TrackpadSettings.magnifier].
     */
    val spaceCursorMagnifier: Boolean = true,
    /**
     * How far a backspace swipe drags before the first word goes.
     *
     * Later words come cheaper on a fixed curve derived from this one, so a
     * single number tunes the whole gesture: raise it if words disappear by
     * accident, lower it if clearing a sentence is a marathon. 72 dp is the
     * long-standing constant, which is a long pull on a small phone and a
     * twitch on a tablet.
     */
    val backspaceWordStepDp: Int = 72,
    /**
     * What one step of the backspace swipe takes off: a whole word, or a
     * single character (issue #36).
     *
     * Character steps are the finer instrument — deleting "teh" out of the
     * middle of a sentence without losing the word — and word steps clear a
     * sentence in one pull. Neither is right for everyone, so it is a choice
     * rather than a curve.
     */
    val backspaceSwipeUnit: BackspaceSwipeUnit = BackspaceSwipeUnit.WORD,
    /**
     * The swipe selects what it is about to delete and only deletes it when
     * the finger lifts, instead of deleting as it goes.
     *
     * On, the gesture is undoable while the finger is still down: dragging
     * back to the right gives the text back, so a swipe that went one word too
     * far costs nothing. It also answers "how much further do I have to drag",
     * which is the whole complaint issue #36 opens with.
     *
     * Off restores the old behavior, deleting one unit per step as the finger
     * passes it. Worth having for editors that handle a selection badly, and
     * for anyone who does not want the field flashing highlighted text.
     */
    val backspaceSwipePreview: Boolean = true,
    /**
     * How far a backspace swipe drags per character, when
     * [backspaceSwipeUnit] is [BackspaceSwipeUnit.CHARACTER].
     *
     * Its own number rather than a fraction of [backspaceWordStepDp]: a
     * character is a much smaller thing to delete than a word, so the two
     * gestures want different distances, and there is no acceleration here —
     * every character costs the same pull.
     */
    val backspaceCharStepDp: Int = 20,
    /**
     * A held delete key clears whole words instead of characters, once the
     * repeat starts (issue #216).
     *
     * The tap is untouched either way — one press is still one character, so
     * the precise thing a delete key is for stays where it was. Only the
     * repeat changes, which is the part nobody holds down for one letter.
     *
     * Off by default: the character repeat is what every keyboard does, and
     * word-at-a-time under a finger is fast enough to be startling for anyone
     * who did not ask for it. [KeyRepeatSettings.wordDeleteMs] is how fast it
     * goes when it is on.
     *
     * Covers ⌦ as well as backspace (issue #226), each clearing the way its
     * own key points.
     */
    val deleteHoldDeletesWords: Boolean = false,
    /**
     * Dragging sideways on the ⌦ key deletes forward, the way the same drag on
     * backspace deletes behind (issue #226).
     *
     * The finger travels the way the deletion travels, so the gesture is a
     * rightward drag here and a leftward one on backspace. Everything else
     * about it — [backspaceSwipeUnit], [backspaceSwipePreview] and the two
     * step distances — is shared with backspace rather than doubled: the two
     * keys are one gesture pointed two ways, and a user who tunes the pull on
     * one has said what they want from the other.
     */
    val forwardDeleteSwipe: Boolean = true,
)

/**
 * The trackpad tool (issue #39), grouped into its own object (see
 * [CameraSettings] for why). DataStore keys stay flat.
 *
 * The panel turns the key area into a pointing surface: a drag moves the caret
 * by the distance the finger travelled, a hold and drag selects, two fingers
 * move by words. The two step sizes are the whole of its feel, so they are
 * separate: a line is taller than a character is wide, and a thumb drifts more
 * across than down.
 */
data class TrackpadSettings(
    /**
     * How far the finger travels per character, sideways. Smaller moves the
     * caret faster. Sits beside [TextEditingSettings.spaceCursorStepDp], which
     * is the same knob for the spacebar swipe, and starts a touch finer
     * because the surface is the whole key area rather than one key.
     */
    val stepXDp: Int = 12,
    /**
     * How far the finger travels per line, up or down. Coarser than the
     * horizontal step on purpose: a line move is a bigger jump in the text, and
     * a finger dragging sideways wobbles vertically more than it means to.
     */
    val stepYDp: Int = 28,
    /**
     * A press and hold on the toolbar's Trackpad tool opens the panel for as
     * long as the finger stays down and closes it on release, for a quick
     * nudge with the other thumb. A tap toggles it either way.
     *
     * On, that hold is spoken for, so the tool's settings page is reached from
     * the Tools screen or a toolbox hold instead, the same trade
     * [TextEditingSettings.selectionModeHold] makes.
     */
    val holdToOpen: Boolean = true,
    /**
     * Two quick taps on the surface select the word at the caret, three the
     * line. Off, taps on the surface do nothing, for anyone whose drags start
     * with a tap they did not mean.
     */
    val multiTap: Boolean = true,
    /** A tick of haptic feedback for every character or line the caret moves. */
    val haptics: Boolean = true,
    /** Draw the finger's trail and a crosshair on the surface while dragging. */
    val trail: Boolean = true,
    /**
     * A magnifier over the caret while a drag moves it (discussion #303): the
     * line around the caret, enlarged, in a bubble over the text, so the finger
     * on the pad can see where the caret is without looking for it.
     */
    val magnifier: Boolean = true,
)

/**
 * Per-app language memory, grouped into its own object (see [CameraSettings] for
 * why). DataStore keys stay flat.
 *
 * When [enabled], an explicit language switch while typing in an app is
 * remembered against that app's package name, and restored the next time a field
 * in the same app is focused. Apps with no stored pick follow the global
 * last-used layout ([KeyboardSettings.activeLayoutId]).
 */
data class PerAppLanguageSettings(
    /** Remember and restore the last explicitly-picked layout per app. */
    val enabled: Boolean = false,
    /** Package name → last explicitly-selected layout id. */
    val layoutByPackage: Map<String, String> = emptyMap(),
)

/** Bounds for the Morse commit pause, in ms; the settings slider shares them. */
val MorseCommitMsRange = 300..2000

/** One corner of the docked keyboard, on screen (left is left, in any language). */
enum class BoardCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    ;

    /** Caption for this choice; resolve it where it is drawn. */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            TOP_LEFT -> R.string.core_settings_board_corner_top_left_label
            TOP_RIGHT -> R.string.core_settings_board_corner_top_right_label
            BOTTOM_LEFT -> R.string.core_settings_board_corner_bottom_left_label
            BOTTOM_RIGHT -> R.string.core_settings_board_corner_bottom_right_label
        }
}

/** Bounds for the docked keyboard's corner radii, in dp; 0 is square. */
val BoardCornerRadiusRange = 0..40

/** Bounds for [LayoutBehaviorSettings.globeTypingGuardMs]; 0 is off. The slider shares them. */
val GlobeTypingGuardMsRange = 0..1000

/** How many languages the user said they type in during onboarding. */
enum class PersonaLanguages { UNSET, ONE, MANY }

/** How much keyboard the user asked for during onboarding. */
enum class PersonaDepth { UNSET, MINIMAL, BALANCED, POWER }

/** How private the user asked the keyboard to be during onboarding. */
enum class PersonaPrivacy { UNSET, STANDARD, STRICT }

/**
 * Onboarding state that outlives the wizard, grouped into its own object (see
 * [CameraSettings] for why). DataStore keys stay flat.
 *
 * The persona answers gate which wizard pages show (on first run and on
 * replay) and order the discovery cards. UNSET means the question was never
 * answered; the wizard treats it as the middle path. Future onboarding-related
 * fields belong here rather than on [KeyboardSettings], whose constructor
 * sits near the `copy$default` slot ceiling (see the note on
 * [KeyboardSettings.photoBackground]).
 */
data class OnboardingSettings(
    val personaLanguages: PersonaLanguages = PersonaLanguages.UNSET,
    val personaDepth: PersonaDepth = PersonaDepth.UNSET,
    val personaPrivacy: PersonaPrivacy = PersonaPrivacy.UNSET,
)

/**
 * How the theme gallery lays out a theme family. [AUTO] follows the
 * onboarding persona and is the default, so the choice made in the wizard
 * shapes the gallery without the wizard writing anything here.
 */
enum class ThemeGalleryStyle { AUTO, GROUPED, FLAT }

/**
 * Preferences about the settings app's own screens — nothing here reaches
 * the keyboard. Grouped for the ceiling reason on
 * [KeyboardSettings.photoBackground]; future settings-app-UI fields belong
 * here rather than on [KeyboardSettings] directly.
 */
data class AppUiSettings(
    val themeGalleryStyle: ThemeGalleryStyle = ThemeGalleryStyle.AUTO,
    /**
     * Which settings folds the user has opened, as "<route>/<key>". A fold
     * closed by default costs a power user a press on every visit unless it
     * remembers; it belongs here with the other settings-app-only state.
     */
    val advancedOpen: Set<String> = emptySet(),
    /**
     * Which size tier a word-list download offers first.
     *
     * The tier used to be per-composition state that reset to LARGE on every
     * visit, so someone who wants the whole list re-picked it for each
     * language and again after every scroll that dropped the row. It belongs
     * here rather than on [KeyboardSettings]: it is a download parameter for
     * one settings screen and nothing in the keyboard reads it.
     */
    val defaultWordlistSize: DictionaryCatalog.DictionarySize =
        DictionaryCatalog.DictionarySize.LARGE,
    /**
     * The personal dictionary screen's order (#194). Remembered for the same
     * reason as [defaultWordlistSize]: someone cleaning out their newest
     * words wants the list that way on the next visit too, and nothing in
     * the keyboard reads it.
     */
    val dictionarySort: DictionarySort = DictionarySort.MOST_USED_FIRST,
    /**
     * Whether settings rows and screen headings carry their coloured icon
     * tiles. Off is the plain list of words the app had before the tiles
     * arrived, for readers who find a column of colour more noise than help.
     * Only the settings app reads it; the keyboard's own icons are untouched.
     */
    val rowIcons: Boolean = true,
    /**
     * Whether a row's icon and name fly into the heading of the screen it
     * opens. Off takes the flights out *and* the shared-transition layout
     * that hosts them: that layout measures the whole settings tree twice on
     * every pass, flights or not, which is most of the lag a slow phone
     * shows opening a screen. The plain slide between screens stays.
     */
    val screenTransitions: Boolean = true,
)

/** What the symbol row's height slider offers, matching the number row's. */
val SymbolRowHeightRange = 28..64

/**
 * How many rows of symbols the symbol row may stack (issue #83). One is the
 * row as it always was; four is where the stack is taller than the key grid
 * it sits over.
 */
val SymbolRowLinesRange = 1..4

/** How a symbol row of more than one line scrolls sideways (issue #83). */
enum class SymbolRowScroll {
    /** One scroll for the whole stack: the lines move as a grid. */
    TOGETHER,

    /** Each line scrolls on its own. */
    SEPARATE,
}

/** How long a mode picked by hand from the Modes tool stays on. */
enum class ManualModeDuration {
    /** Until the user moves to another app. What the keyboard always did. */
    UNTIL_APP_CHANGES,

    /** Until the user picks a different mode, or none. */
    UNTIL_CHANGED,
}

/**
 * The rows above the keys, and the modes that dress them. Grouped for the
 * ceiling reason on [KeyboardSettings.photoBackground] — the flat list is three
 * or four fields from the JVM limit on `copy$default`, so this domain takes one
 * slot once and future row and mode settings cost nothing.
 */
data class RowSettings(
    /**
     * Height of the symbol row.
     *
     * Hard-coded at 40 dp while the number row beside it had a 32-100 dp
     * slider, so the two rows could not be made to match.
     */
    val symbolRowHeightDp: Int = 40,
    /**
     * How many lines of symbols the symbol row stacks (issue #83); each is
     * [symbolRowHeightDp] tall. One line is the row as shipped. More lines put
     * more of a set on screen at once without a scroll, at a row's height each.
     */
    val symbolRowLines: Int = 1,
    /**
     * How a symbol row of more than one line scrolls (issue #83). Meaningless
     * on one line, and left untouched by it, so a stack that is put back keeps
     * its scroll.
     */
    val symbolRowScroll: SymbolRowScroll = SymbolRowScroll.TOGETHER,
    /**
     * How long a mode picked by hand from the Modes tool lasts.
     *
     * The keyboard cleared it on the next app switch with no way to say
     * otherwise, which is right for a mode that was picked in passing and
     * wrong for one picked deliberately.
     */
    val manualModeDuration: ManualModeDuration = ManualModeDuration.UNTIL_APP_CHANGES,
    /**
     * The dictionary bar (issue #51): a row over the keys listing every
     * dictionary on the device — a language's word list, its emoji keywords,
     * each imported list — as chips that switch it on and off in place. Off
     * by default: it is a power user's row, and it costs a row's height.
     */
    val dictionaryBarEnabled: Boolean = false,
    /**
     * Which language's dictionaries the bar shows: a language id, or empty
     * for every enabled language at once. Picked from the bar's own dropdown
     * and remembered, so the bar opens on the language it was last filtered
     * to.
     */
    val dictionaryBarFilter: String = "",
)

/** Whether [langId]'s emoji keyword packs are read; see [EmojiSettings.disabledKeywordLangs]. */
fun EmojiSettings.keywordsEnabledFor(langId: String): Boolean = langId !in disabledKeywordLangs

/**
 * Whether the theme gallery groups families into one card with a swatch per
 * look, or lists every look as its own card. AUTO resolves from the persona
 * quiz at read time — "keep it simple" gets the flat list, everyone else
 * (including a user who never answered) gets the grouped cards — so existing
 * installs pick their side retroactively and an explicit choice still wins.
 */
fun KeyboardSettings.themeGalleryGrouped(): Boolean = when (appUi.themeGalleryStyle) {
    ThemeGalleryStyle.GROUPED -> true
    ThemeGalleryStyle.FLAT -> false
    ThemeGalleryStyle.AUTO -> onboarding.personaDepth != PersonaDepth.MINIMAL
}

/**
 * What clipboard history does with a clip that holds a secret.
 *
 * The default is deliberately the middle option rather than [NEVER_SAVE]: a
 * password pasted into the wrong box is the everyday reason to want it back for
 * ten seconds, and refusing to remember it at all trades a real convenience for
 * a risk that a five-minute expiry already closes.
 */
enum class SensitiveClipHandling {
    /** Kept like any other clip — the flag and the detector are ignored. */
    KEEP,

    /**
     * Saved, but drawn masked in the panel and swept after
     * [ClipboardSettings.sensitiveExpiryMinutes] instead of the history expiry.
     */
    SHORT_LIVED,

    /** Never written to history at all. */
    NEVER_SAVE,
    ;

    /** Caption for this choice; resolve it where it is drawn. */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            KEEP -> R.string.core_settings_sensitive_clip_keep_label
            SHORT_LIVED -> R.string.core_settings_sensitive_clip_short_lived_label
            NEVER_SAVE -> R.string.core_settings_sensitive_clip_never_save_label
        }

    /** The line under [labelRes]; resolve it where it is drawn. */
    @get:StringRes
    val detailRes: Int
        get() = when (this) {
            KEEP -> R.string.core_settings_sensitive_clip_keep_subtitle
            SHORT_LIVED -> R.string.core_settings_sensitive_clip_short_lived_subtitle
            NEVER_SAVE -> R.string.core_settings_sensitive_clip_never_save_subtitle
        }
}

/**
 * Where a copied one-time code is allowed to appear as the recently-copied
 * paste chip.
 *
 * A code alone in the clip reads as a secret ([ClipboardSettings.detectSensitive]),
 * and a secret is otherwise never offered as a chip — the chip sits in view
 * above the keys while you type something else. That rule was written for a
 * password out of a manager, and it holds for one: a generated password never
 * qualifies for this chip whatever the option, because only a *bare code*
 * shape does.
 *
 * What is left is the everyday case the rule was never aimed at — a
 * verification code the user copied by hand, which they copied for exactly one
 * reason. Withholding it does not keep the code off the device: it is one tap
 * away in the clipboard panel either way. So the default offers it wherever
 * the user is typing, and [CODE_FIELDS] stays for people who would rather a
 * code never appear over the keys of a message.
 */
enum class CopiedCodeChip {
    /** Never offered; a code-shaped clip is reachable only from the panel. */
    OFF,

    /** Only in a box that asks for a code, where the code is all you type. */
    CODE_FIELDS,

    /** In any field, like any other copied text. */
    ANY_FIELD,
    ;

    /** Caption for this choice; resolve it where it is drawn. */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            OFF -> R.string.core_settings_copied_code_chip_off_label
            CODE_FIELDS -> R.string.core_settings_copied_code_chip_code_fields_label
            ANY_FIELD -> R.string.core_settings_copied_code_chip_any_field_label
        }
}

/**
 * How the clipboard panel lays its history out.
 */
enum class ClipboardView {
    /** Two columns of cards, packed independently: images read best this way. */
    GRID,

    /** One clip per row across the full width: long text reads best this way. */
    LIST,
    ;

    /** Caption for this choice; resolve it where it is drawn. */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            GRID -> R.string.core_settings_clipboard_view_grid_label
            LIST -> R.string.core_settings_clipboard_view_list_label
        }
}

/** The time a clip in the panel shows under its text, if any. */
enum class ClipTimeLabel {
    /** No time on the clips. */
    OFF,

    /** How long ago it was copied: "5 min ago". */
    COPIED,

    /** How long until it expires: "2 h left". Nothing on a clip that never does. */
    EXPIRES,
    ;

    /** Caption for this choice; resolve it where it is drawn. */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            OFF -> R.string.core_settings_clip_time_off_label
            COPIED -> R.string.core_settings_clip_time_copied_label
            EXPIRES -> R.string.core_settings_clip_time_expires_label
        }
}

/** Lines of text a clip may show in the panel; 0 is the view's own (see [ClipboardSettings.previewLines]). */
val ClipPreviewLinesRange = 0..20

/** Columns the grid view may have. */
val ClipGridColumnsRange = 1..4

/**
 * The stops of the per-clip text limit, in characters; 0 is no limit. A
 * slider over these rather than over every number, since nobody means 13,417.
 * 20,000 is Gboard's own limit.
 */
val ClipMaxTextCharsSteps = listOf(0, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000)

/**
 * Clipboard-tool settings — history capture, the panel, and the paste chip on
 * the suggestion strip — grouped into their own object (see [CameraSettings]
 * for why). DataStore keys stay flat.
 */
data class ClipboardSettings(
    /** Save copied text/images/files for quick paste from the clipboard tool. */
    val history: Boolean = true,
    /**
     * How long the recently-copied paste chip stays on the suggestion strip,
     * in seconds. 0 means until it is pasted or dismissed.
     *
     * Five minutes, hard-coded. Generous is right for someone writing the
     * message they are about to paste into, and wrong for anyone who would
     * rather what they copied stopped being on screen — which for a password
     * or an address is the whole point.
     */
    val pasteChipSeconds: Int = 5 * 60,
    /** Remove unpinned items after this many hours (0 = never). */
    val expiryHours: Int = 24,
    /**
     * How many unpinned entries history keeps; older ones fall off the end.
     * The other half of the bound [expiryHours] sets — a busy day of copying
     * can pile up hundreds of clips well inside the expiry window, and a panel
     * that long is not history, it is a haystack.
     */
    val maxItems: Int = ClipboardStore.DEFAULT_MAX_ITEMS,
    /**
     * What to do with a clip the copying app marked sensitive (Android 13's
     * `ClipDescription.EXTRA_IS_SENSITIVE`, which is what a password manager
     * sets on a copied password).
     *
     * [SensitiveClipHandling.KEEP] by default: hiding and expiring secrets is
     * an opt-in for the privacy-minded, not something every user pays for with
     * masked clips they cannot read or edit.
     */
    val sensitiveHandling: SensitiveClipHandling = SensitiveClipHandling.KEEP,
    /**
     * Also apply [sensitiveHandling] to clips that *look* like a password or a
     * bare one-time code, not just the ones flagged by their source. Most
     * password managers still predate the flag, and a code copied by hand out
     * of a message carries no flag at all.
     */
    val detectSensitive: Boolean = true,
    /**
     * How long a sensitive clip survives, in minutes. Independent of
     * [expiryHours] and never capped by it: a short leash has to hold even when
     * history is set to keep everything.
     */
    val sensitiveExpiryMinutes: Int = 5,
    /** Fetch page titles for copied links and show them in the clipboard panel. */
    val linkPreviews: Boolean = false,
    /**
     * Record which app a clip was copied from (shown in the press-and-hold info
     * popup). Off by default: needs the Usage Access special permission and is a
     * best-effort guess of the foreground app at copy time.
     */
    val trackSource: Boolean = false,
    /**
     * Offer the most recently copied text as a paste chip on the suggestion
     * strip (Gboard style), so a fresh copy is one tap from being pasted.
     */
    val suggestRecent: Boolean = true,
    /**
     * The one exception to "a secret never gets a strip chip": a copied
     * one-time code *is* offered. See [CopiedCodeChip] for where, and why the
     * rule bends here and nowhere else.
     */
    val copiedCodeChip: CopiedCodeChip = CopiedCodeChip.ANY_FIELD,
    /** List pinned entries at the end instead of the top of the clipboard panel. */
    val pinnedLast: Boolean = false,
    /** Show a search bar at the top of the clipboard panel to filter history. */
    val search: Boolean = false,
    /** Show user screenshots in the clipboard alongside copied text and images. */
    val userScreenshots: Boolean = false,
    /**
     * Delete a clip from history *and* from the system clipboard the moment it
     * is pasted into a password field. A password pasted out of a manager is
     * the single most sensitive thing the clipboard ever holds, and it would
     * otherwise sit there — readable by every app — until it expired. Off by
     * default, like the rest of the password handling: an opt-in for the
     * privacy-minded.
     */
    val clearAfterPasswordPaste: Boolean = false,
    /**
     * Pull one-time codes, phone numbers and links out of clips and offer them
     * as their own chips above the history, so the six digits inside a
     * verification SMS are one tap away instead of a copy-edit-paste.
     */
    val detectEntities: Boolean = true,
    /**
     * The phone-number shapes to keep, as masks (`+880 1XXX-XXXXXX`). Empty
     * means every number-shaped run counts, which is where the detector's false
     * positives come from — an invoice total and a tracking id have the shape
     * of a phone number too.
     *
     * The list starts with one mask worked out from the device's region (see
     * [PhoneFormats.forRegion]), not empty, so the detector knows which country
     * the user lives in before being told. Empty is still reachable, by
     * deleting that mask. See `PhoneFormats`.
     */
    val phoneFormats: Set<String> = emptySet(),
    /**
     * Let the clipboard panel take the whole keyboard, hiding the toolbar the
     * way the emoji and media panels can — the reclaimed rows go to more
     * history cards. On by default: the panel pays for the toolbar's row with
     * its own back header, and picking the right clip is easier the more of
     * them are on screen at once. Turning it off keeps the toolbar in reach.
     */
    val fullBleed: Boolean = true,
    /**
     * Cards in two columns, or one clip per row. The panel's own toggle
     * writes this too, so the choice survives the panel closing.
     */
    val view: ClipboardView = ClipboardView.GRID,
    /**
     * Number every clip by its place in the history, so the one meant is the
     * one tapped. The number belongs to the clip, not to the row it lands in:
     * a search keeps each clip's own number rather than counting from 1 again.
     * Off by default.
     */
    val showNumbers: Boolean = false,
    /**
     * Offer an Undo bar for a few seconds after a clip is deleted from the
     * panel (#327). A swipe deletes with no confirmation, and one made while
     * scrolling is easy to make by accident. On by default: it only shows up
     * when something was just deleted.
     */
    val undoDelete: Boolean = true,
    /**
     * Swiping a card sideways deletes it (#344). On by default. Off, a
     * sideways drag does nothing, and the press-and-hold popup gains a Delete
     * button in its place, for anyone who kept losing clips to a swipe made
     * while scrolling. The bin circle on each card is there either way.
     */
    val swipeToDelete: Boolean = true,
    /**
     * Lines of text each clip shows in the panel. 0, the default, is the
     * view's own: six on a grid card, three in a list row. See
     * [ClipPreviewLinesRange].
     */
    val previewLines: Int = 0,
    /**
     * Columns of cards in the grid view, 1 to 4 ([ClipGridColumnsRange]). Two
     * by default, as before; a wide screen or short clips suit more. The list
     * view is always one clip per row.
     */
    val gridColumns: Int = 2,
    /** The time each clip shows: none, when it was copied, or when it expires. */
    val timeLabel: ClipTimeLabel = ClipTimeLabel.OFF,
    /**
     * The most characters of text one clip keeps, 0 for no limit (the
     * default). A longer copy is stored cut to this length. Gboard cuts at
     * 20,000; here nothing is cut unless the user asks, since a clip cut short
     * pastes something other than what was copied. See [ClipMaxTextCharsSteps].
     */
    val maxTextChars: Int = 0,
)

/**
 * Emoji behaviour split off into its own object because [KeyboardSettings]
 * sits at the JVM copy() slot ceiling (see [CameraSettings]). DataStore keys
 * stay flat.
 */
data class EmojiSettings(
    /**
     * Default skin tone shown for toned emoji everywhere they are drawn — the
     * emoji panel's grid, the suggestion strip and emoji search.
     * [EmojiSkinTone.NONE] keeps the neutral yellow base.
     */
    val defaultSkinTone: EmojiSkinTone = EmojiSkinTone.NONE,
    /**
     * Let the tone last picked for an emoji (from the panel's long-press
     * popup) override [defaultSkinTone]. On by default: a tone picked for one
     * emoji is a deliberate, more specific choice than the global default.
     */
    val toneOverrideByLastUsed: Boolean = true,
    /**
     * Close the current panel and return to the keys immediately after a single
     * insert, instead of staying open for a run. Applies to the emoji panel (one
     * emoji, then back to typing) and the clipboard panel (one paste, then back).
     */
    val closeAfterInsert: Boolean = false,
    /**
     * The narrowest a cell in the emoji panel's grids may be, in dp. The grids
     * fit as many columns as the width allows at this size, so a smaller cell
     * packs more emoji on screen and a larger one spreads them out. A cell
     * still grows past this when [gridEmojiSize] needs the room, so the two
     * settings cannot combine into clipped glyphs. See [EmojiGridCellSizeRange].
     */
    val gridCellSize: Int = 44,
    /**
     * How many recently used emoji the history tab keeps. 32 is one panel row
     * on a small phone and four on a tablet, so the same number reads as a
     * short list to one user and a wall to another. See [EmojiRecentsRange].
     */
    val recentsLimit: Int = 32,
    /**
     * Columns in the image-search grid.
     *
     * The only media grid with a fixed column count: GIF results are laid out
     * as justified rows so each preview keeps its aspect ratio, and stickers
     * size themselves to the pack. Three suits a phone; a tablet or a
     * landscape screen can take more, and two gives bigger previews to look
     * at before sending.
     */
    val mediaGridColumns: Int = 3,
    /**
     * Size the emoji panel's grids draw each emoji at, in sp — the category
     * tabs, history and search results alike. The long-press popup keeps its
     * own fixed size. See [EmojiGridEmojiSizeRange].
     */
    val gridEmojiSize: Int = 28,
    /**
     * Hide emoji nothing on the device can draw (they render as a blank "tofu"
     * box) from the panel, search and suggestions. An emoji only the *chosen*
     * emoji font lacks is not hidden — it is drawn in the phone's own emoji
     * font instead — so this is about the phone's coverage, and importing a
     * complete emoji font under Emoji → Emoji font is the way to widen it.
     */
    val hideUnrenderable: Boolean = false,
    /**
     * Let the emoji row scroll sideways to reach the emoji past its visible
     * slots. On by default: the row is seeded with more emoji than fit across a
     * phone, and dropping the rest silently is worse than a swipe that can
     * occasionally slide the row under a tap. Off shows exactly [barCount]
     * emoji and drops the rest, so the row never moves.
     */
    val barScrollable: Boolean = true,
    /**
     * How many emoji the row fits across its width — equally, how tightly it
     * packs them, since each glyph shrinks to its slot. Beyond this the emoji
     * are only reachable with [barScrollable] on. See [EmojiBarCountRange].
     */
    val barCount: Int = 8,
    /**
     * Add Kaomoji ( ͡° ͜ʖ ͡°) and Emoticons :-) tabs to the end of the emoji
     * panel's tab strip. Off by default — they push the tab strip narrower,
     * and most users never reach for them.
     */
    val kaomojiTabs: Boolean = false,
    /**
     * Bumped whenever an emoji keyword pack is imported, downloaded or
     * removed. Not a preference — the IME watches it to know its merged
     * catalog is stale, the same trick [KeyboardSettings.customDictVersion]
     * plays for word lists.
     */
    val keywordPackVersion: Int = 0,
    /**
     * Fetch the emoji dictionary for a language when it is enabled, and for
     * any enabled language still missing one.
     *
     * On by default: without keywords in their own language, emoji search only
     * answers English, and the packs are 148 B to 112 KB compressed — smaller
     * than one photo. Off leaves every download to the buttons under
     * Settings › Emoji › Emoji keywords.
     *
     * Narrower than [KeyboardSettings.autoDownloadLanguageData], which turns
     * off every automatic download at once; this one is the emoji half of it,
     * and both have to be on for a pack to arrive unasked.
     */
    val autoDownloadKeywords: Boolean = true,
    /**
     * Languages whose emoji keyword packs — the downloaded dictionary and any
     * imports — are switched off (issue #51). The files stay on disk; the
     * keyboard just leaves them out of the merged catalogue, so emoji search
     * and suggestions stop answering in that language until it is switched
     * back on. Stored as the exceptions, like [SuggestionStripSettings.
     * importedOnlyLangs], so an untouched language needs no entry.
     */
    val disabledKeywordLangs: Set<String> = emptySet(),
    /**
     * Bumped when a settings import rewrites the emoji history file, so the
     * running keyboard drops its in-memory copy and re-reads it. Not a
     * preference — the same trick [keywordPackVersion] plays for the packs.
     */
    val usageVersion: Int = 0,
    /**
     * Offer Google's animated version of an emoji on its long press, as a GIF
     * sticker. Fetched from fonts.gstatic.com the moment it is asked for —
     * around 800 KB apiece, so nothing is downloaded until a long press.
     *
     * On by default, and only ever visible on a field that accepts images.
     */
    val animated: Boolean = true,
    /**
     * Offer "Send as sticker" on an emoji's long press: the emoji itself, drawn
     * at 512×512 in the keyboard's own emoji font and sent as a WebP sticker.
     *
     * On by default. Nothing is downloaded for it — the glyph is already on the
     * device — but it only appears on a field that accepts images.
     */
    val sendAsSticker: Boolean = true,
    /**
     * The order of the panel's category tabs, as catalog category ids. Empty
     * — the default — leaves them in the catalog's own Unicode order.
     *
     * A partial list is honoured rather than rejected: whatever it names is
     * placed in that order, and the rest of the catalog falls in around it.
     * See `EmojiOrder.merge`, which is where the two are reconciled for both
     * the panel and the screen that edits this.
     */
    val categoryOrder: List<String> = emptyList(),
    /**
     * Category ids whose tab the panel does not draw. Hiding every category is
     * refused at both ends — the editor keeps the last one switched on, and
     * `EmojiOrder.categories` ignores a hidden set that would empty the panel.
     */
    val hiddenCategories: Set<String> = emptySet(),
    /**
     * Per category, the order its emoji are laid out in — catalog order where
     * a category is absent, which is every category until someone drags one.
     *
     * Stored whole for a category the user has touched, not as a list of
     * moves: 270 emoji is the largest category there is, so the honest
     * representation costs a couple of kilobytes and cannot drift the way a
     * replayed move list does.
     */
    val categoryEmojiOrder: Map<String, List<String>> = emptyMap(),
)

/** Bounds for [EmojiSettings.barCount]; the settings slider shares them. */
val EmojiBarCountRange = 3..16

/** Bounds for [EmojiSettings.gridCellSize]; the settings slider shares them. */
val EmojiGridCellSizeRange = 36..64

/** Bounds for [EmojiSettings.recentsLimit]; the settings slider shares them. */
val EmojiRecentsRange = 8..96

/** Bounds for [EmojiSettings.gridEmojiSize]; the settings slider shares them. */
val EmojiGridEmojiSizeRange = 20..36

/**
 * Bounds for [GestureSettings.pickerChoices]. The settings slider, the service
 * and the picker's own target array all share them, so the three can never
 * disagree about how many words a stroke may offer.
 */
val GlidePickerChoicesRange = 2..5

/** Bounds for [GestureSettings.pickerDwellMs]; the settings slider shares them. */
val GlidePickerDwellMsRange = 150..1000

/**
 * Bounds for the decoder's three tolerances — [GestureSettings.startRadius],
 * [GestureSettings.endRadius] and [GestureSettings.nearRadius] — in key
 * widths. The settings sliders and the setters below share them, so a value
 * the slider can reach is never one the repository refuses to store (#241).
 *
 * The floor is deliberately above zero: a radius of 0 admits no key at all and
 * would turn glide typing off from inside a slider.
 */
val GlideRadiusRange = 0.5f..4f

/**
 * Bounds for [GestureSettings.dwellFull], as multiples of a stroke's own mean
 * step. The floor is above zero because the weight is a divisor, and the
 * ceiling is a hesitation so long that only a deliberate stop reaches it.
 */
val GlideDwellFullRange = 1f..8f

/**
 * Bounds for [GestureSettings.loopMinArc], in key widths: the least path a
 * curl has to hold before it is a loop rather than tremor. Covers the band
 * `GlideTuningSweepTest` swept, 0.7 to 1.6, with room either side.
 */
val GlideLoopMinArcRange = 0.5f..2f

/**
 * Bounds for [GestureSettings.loopExtent], in key widths. The ceiling is
 * deliberately near one key pitch: three neighbouring keys visited in a ring
 * draw a real loop of extent about one, so a limit far above that reads a
 * word like `murderer` as a doubled letter.
 */
val GlideLoopExtentRange = 0.4f..1.2f

/**
 * Bounds for [GestureSettings.loopRadius], in key widths: how near a key the
 * loop's centre has to sit for the loop to be about that key. A circle drawn
 * through a key centres about a third of a key off it, so the floor has to
 * clear that, and the ceiling stops short of the next key along.
 */
val GlideLoopRadiusRange = 0.35f..1f

/**
 * Bounds for [GestureSettings.wiggleExtent], in key widths. The ceiling stays
 * under one key pitch: a wider wiggle would read a word that goes back and
 * forth across two adjacent keys as one doubled letter.
 */
val GlideWiggleExtentRange = 0.2f..0.9f

/**
 * Bounds for [GestureSettings.wiggleWeight]: what a wiggle is worth as a
 * doubled-letter mark, against a loop's 1. The floor is above zero because
 * zero is the reading switched off, which is what the toggle is for.
 */
val GlideWiggleWeightRange = 0.1f..1f

/**
 * Bounds for [GestureSettings.shapesPerWord]. The top is the shape store's own
 * ceiling, so a value the slider reaches is always one the store keeps.
 */
val GlideShapesPerWordRange = 1..GlideShapeStore.MAX_SHAPES_PER_WORD

/** Glide-typing behaviour and swipe-trail appearance. See [KeyboardSettings.gesture]. */
data class GestureSettings(
    /**
     * Swiping over the spacebar mid-glide commits the current word and starts a
     * new one, so several words can be glided in one unbroken stroke. On by
     * default; off makes a swipe that crosses the spacebar decode as one word.
     */
    val spaceGlideMultiWord: Boolean = true,
    /**
     * Passing over the shift key mid-glide capitalizes the word, so a proper
     * noun can be swiped without stopping to tap shift first (#115).
     *
     * Gesture typing has no way to say "capital" without leaving the stroke:
     * tapping shift, then swiping, is two gestures for one word, and it is the
     * flow-breaking step that made people give up on capitalizing swiped words
     * at all. This is Swype's answer — draw through the shift key on the way
     * and carry on — and it costs nothing when unused: the key has to actually
     * be crossed, and its points are dropped from the word the way the
     * spacebar's are, so the detour never spells anything.
     *
     * Crossing it twice shouts the word, exactly as tapping shift twice does.
     */
    val shiftGlideCapitals: Boolean = true,
    /**
     * How a crossing of the shift key is read while [shiftGlideCapitals] is
     * on: the whole word, or one letter per crossing (#163). See
     * [ShiftGlideMode].
     */
    val shiftGlideMode: ShiftGlideMode = ShiftGlideMode.WORD,
    /**
     * When a swipe is genuinely ambiguous, ask instead of committing.
     *
     * Some strokes have no right answer: on a fixed Bengali layout ক and খ share
     * a key, so a word and its aspirated twin are drawn identically, and on any
     * layout a hurried stroke can fit two words equally well. Committing the
     * likelier one and leaving the user to notice is the worst of the options.
     *
     * With this on, a stroke whose best candidate barely leads its runner-up
     * puts the top few words under the fingertip: hold still without lifting and
     * they appear, slide onto one and lift to take it. Lifting without choosing
     * commits the leader as before, and the same words stay on the suggestion
     * strip either way, so nothing is lost by ignoring it.
     *
     * Once the words are up the stroke is finished: sliding to a word does not
     * redraw it, and a finger dragged down away from the words and lifted
     * types nothing at all.
     */
    val ambiguityPicker: Boolean = true,
    /**
     * How long the finger has to hold still, in ms, before an unclear stroke
     * offers its choices. Long enough that pausing to think mid-word does not
     * trip it, short enough to feel like an answer rather than a wait. Default
     * 350 ms; range [GlidePickerDwellMsRange]. Does nothing while
     * [ambiguityPicker] is off.
     */
    val pickerDwellMs: Int = 350,
    /**
     * How close a call a stroke has to be before holding still asks. See
     * [GlidePickerSensitivity]; [GlidePickerSensitivity.CLOSE_CALLS] by
     * default, which is the rule every stroke was judged by before this was a
     * choice.
     */
    val pickerSensitivity: GlidePickerSensitivity = GlidePickerSensitivity.CLOSE_CALLS,
    /**
     * A stroke that is not a close call still asks if the finger holds for
     * twice [pickerDwellMs]. On by default, so a word the decoder is sure of
     * can still be second-guessed without lifting; off means a confident
     * stroke never asks however long the finger rests.
     */
    val pickerHoldToAsk: Boolean = true,
    /**
     * How many words the picker offers, best first. Three by default: they fit
     * under a fingertip in one row without the targets shrinking past what a
     * finger can land on; four and five wrap onto a second row above. Range
     * [GlidePickerChoicesRange]. The strip's own slot count is floored at this
     * so a narrow strip cannot starve the picker.
     */
    val pickerChoices: Int = 3,
    /**
     * Which key a glide reads as an apostrophe, so "it's" can be drawn as
     * `i → t → ' → s` instead of being guessed from "its". [GlideApostropheKey.OFF]
     * by default, which is exactly today's behaviour.
     *
     * Using the key is never required: the same word drawn without the detour
     * still decodes, and "Fix missing apostrophes" still repairs the
     * contractions it can repair on its own. This is for the ones it cannot,
     * because both spellings are real words.
     */
    val apostropheKey: GlideApostropheKey = GlideApostropheKey.OFF,
    /**
     * Which punctuation key a short straight swipe to `s` starts from to append
     * `'s` to the word behind the caret (issue #169): "developer" becomes
     * "developer's" without a trip to the long press. A gesture of its own, not
     * part of glide typing: it works on a tapped word as well as a glided one,
     * with glide typing off as well as on, and it takes back the space that
     * follows the word before putting the possessive there.
     *
     * Off by default. [GlideApostropheKey.SPACE] is never honoured — a stroke
     * off the spacebar is a spacebar swipe — so the choice is one of the three
     * punctuation keys, independent of [apostropheKey].
     */
    val possessiveKey: GlideApostropheKey = GlideApostropheKey.OFF,
    /**
     * A glided word is followed by a space, so the next word — glided or tapped
     * — starts clean instead of running into it. On by default. The space is
     * the keyboard's, not the user's: punctuation typed straight after takes it
     * back ("hello." not "hello ."), and a space press right after is spent
     * confirming it rather than doubling it.
     */
    val autoSpaceAfterGlide: Boolean = true,
    /**
     * A glided word is spaced off the text in front of it, so two swiped words
     * do not run together and a word swiped onto the end of typed text does
     * not either. Not a setting: it has no preference key and no row, and the
     * global [autoSpaceAfterGlide] switch deliberately leaves it alone — a user
     * who turned the trailing space off did not ask for `helloworld`. Only a
     * keyboard mode's auto-space override turns it off (#184), because that
     * override promises every space in the field is one the user typed.
     */
    val autoSpaceBeforeGlide: Boolean = true,
    /**
     * How far the finger must travel before a press turns into a glide, as a
     * multiple of the system touch slop. Lower is more sensitive (a glide
     * starts sooner); higher needs a more deliberate swipe before it takes over
     * from a tap. Default 2×.
     */
    val startThresholdSlop: Float = 2f,
    /**
     * How long after the last keypress a glide is held back, in ms. During this
     * window right after tapping, a stray slide off a key needs to travel much
     * further before it is read as a swipe-word, so fast tap-typing does not
     * spill into accidental gestures. The extra distance fades to nothing across
     * the window. 0 disables the guard entirely; default 160 ms. Higher makes
     * gliding immediately after typing harder (fewer accidents, but a deliberate
     * swipe right after a tap is slower to start).
     */
    val postTypeCooldownMs: Int = 160,
    /**
     * Handwrite-with-swipes only. For this long after a drawn stroke lifts, a
     * quick tap over the letters is captured as another ink stroke of the same
     * character rather than typing its key — so the dot on an i or j, or the
     * cross on a t, can be added as a separate mark instead of committing a
     * letter. A press that lands after the window types as normal. 0 disables
     * it; default 700 ms.
     */
    val handwriteDotCooldownMs: Int = 700,
    /** Head width of the comet trail, in dp. The tail thins to ~30% of this. */
    val trailWidthDp: Float = 10f,
    /** How long each trail point stays on screen, in ms. Longer = a longer tail. */
    val trailDurationMs: Int = 350,
    /** Peak opacity of the trail, 0..1. */
    val trailOpacity: Float = 0.55f,
    /**
     * Whether the word a glide currently decodes to floats above the fingertip
     * while the stroke is still down.
     *
     * On, as it always was. Off leaves the suggestion strip as the only place
     * the word in progress is shown, which is where it goes on finger-up
     * anyway; the trail and the decode are untouched either way.
     */
    val wordPreview: Boolean = true,
    /**
     * How far above the fingertip that pill sits, in dp.
     *
     * The default is the distance it always kept. A hand is wider than a
     * fingertip, so the number that clears one person's finger buries the pill
     * under another's knuckle: raise it until the word is readable mid-stroke.
     * The pill is drawn inside the key grid, so on the top row a large distance
     * stops at the top of the keyboard rather than climbing past it.
     */
    val wordPreviewOffsetYDp: Int = 56,
    /**
     * Sideways shift of the pill, positive to the right.
     *
     * Screen-space rather than start-relative, for the same reason the key
     * preview's is: this is about which hand holds the phone, not which way the
     * script runs. The pill is still clamped into the grid, so a shift near an
     * edge stops at the edge.
     */
    val wordPreviewOffsetXDp: Int = 0,
    /** Label size in the pill, in sp. */
    val wordPreviewFontSp: Int = 18,
    /**
     * Pill background, as ARGB; null follows the theme's popup colour, which is
     * the default. The pill is drawn over the keys mid-stroke, so on some
     * themes it reads as one more key rather than as an answer — which is what
     * this is for.
     */
    val wordPreviewBackground: Long? = null,
    /** Pill label colour, as ARGB; null follows the theme. See [wordPreviewBackground]. */
    val wordPreviewTextColor: Long? = null,
    /**
     * While a stroke is still down, the suggestion strip carries only the word
     * the decode reads now, instead of the whole list of candidates behind it
     * (issue #84).
     *
     * Off by default, which is what the strip always did: every reading of the
     * stroke sits on it, and they all change as the finger moves. On, the strip
     * holds one word — the same word the pill floats, and the same word a lift
     * commits — so the row stops churning under a stroke that is still being
     * drawn.
     *
     * Nothing is lost by turning it on: the finger lifting puts the alternates
     * back on the strip as it always has, and the ambiguity picker still offers
     * them mid-stroke. Display only, like [wordPreview]: the decode and what a
     * glide types are untouched.
     */
    val stripPreviewOnly: Boolean = false,
    /**
     * How much of the dictionary a swipe may answer with — see
     * [GlideVocabulary]. [GlideVocabulary.LARGE] by default, which is a no-op
     * on every word list of 300k words or fewer, the bundled ones included.
     */
    val vocabulary: GlideVocabulary = GlideVocabulary.LARGE,
    /**
     * Whose words a swipe may answer with — see [GlideSandbox].
     * [GlideSandbox.NORMAL] by default, which is what the decoder always did.
     */
    val sandbox: GlideSandbox = GlideSandbox.NORMAL,
    /**
     * How hard the mid-stroke word resists replacement — see
     * [GlidePreviewSteadiness]. [GlidePreviewSteadiness.LIGHT] by default: the
     * words now drawn on the keys during a stroke make the churn much more
     * visible than it was when only the strip moved.
     */
    val previewSteadiness: GlidePreviewSteadiness = GlidePreviewSteadiness.LIGHT,
    /**
     * Whether a glide may offer a word the stroke has not finished spelling —
     * see [GlideLookAhead]. [GlideLookAhead.OFF] by default: it changes what a
     * lift types, and the research it comes from is clear that a badly tuned
     * version is worse than none.
     */
    val lookAhead: GlideLookAhead = GlideLookAhead.OFF,
    /**
     * Which word a stroke being drawn is coloured rather than merely bolded —
     * see [GlideCommitColor]. [GlideCommitColor.CONFIDENT] by default, which
     * draws nothing at all until [lookAhead] is on and the strip has a colour
     * of its own.
     */
    val commitColor: GlideCommitColor = GlideCommitColor.CONFIDENT,
    /**
     * How far that colour reaches — see [GlideCommitColorScope].
     * [GlideCommitColorScope.STRIP_AND_PILL] by default: the pill is what the
     * eye is on while a stroke is being drawn, so a promise shown only on the
     * strip is a promise mostly unread.
     */
    val commitColorScope: GlideCommitColorScope = GlideCommitColorScope.STRIP_AND_PILL,
    /**
     * How far the stroke's *first* sample may sit from a word's first key, in
     * key widths, for that word to be an answer to the stroke at all (#222).
     *
     * This and the two below are the decoder's three tolerances, the only
     * weights of [GlideBeam.Tuning] a user can move. They are gates rather
     * than scores: inside the radius a word is scored as it always was, and
     * outside it the word is not considered. Widening one lets a sloppier
     * stroke still reach the word it meant; narrowing one drops words the
     * finger never went near, which is faster and stricter.
     *
     * Defaults come off [GlideBeam.Tuning.DEFAULT] so the row's reset restores
     * the value the decoder was measured at. Swept, both anchors sit on a flat
     * plateau above about 1.3, so the useful direction here is downwards:
     * a tight anchor is what costs accuracy.
     */
    val startRadius: Float = GlideBeam.Tuning.DEFAULT.startRadius,
    /**
     * How far the stroke's *last* sample may sit from a word's last key, in key
     * widths. See [startRadius]. Separate from it because the two ends of a
     * stroke are not the same event: a touch-down is placed deliberately, and a
     * lift-off is where a movement happened to stop.
     */
    val endRadius: Float = GlideBeam.Tuning.DEFAULT.endRadius,
    /**
     * How close the stroke must pass to a key, in key widths, for any word
     * through that key to be walked at all. See [startRadius]. This is the
     * path tolerance: it is what decides how much corner cutting a stroke may
     * do before the letter in the corner stops being available.
     */
    val nearRadius: Float = GlideBeam.Tuning.DEFAULT.nearRadius,
    /**
     * How long the finger has to linger on a key, as a multiple of the
     * stroke's own mean step, for the pause to read as a deliberate one
     * (#270).
     *
     * This and the six below are the decoder's three *intents*: the marks a
     * stroke can carry that say a letter is written twice. A pause on the key
     * is one, a small circle on it is the second, and a back-and-forth on it
     * is the third. Shape alone cannot tell `good` from `god` (the o is one
     * key, crossed once), so these are the only evidence there is, and how
     * readily each one registers depends on the hand: a slow, careful glide
     * pauses everywhere, and a quick one barely pauses at all.
     *
     * Relative rather than in milliseconds, on purpose. See
     * [GlideBeam.Tuning.dwellFull], whose default this is; range
     * [GlideDwellFullRange].
     */
    val dwellFull: Float = GlideBeam.Tuning.DEFAULT.dwellFull,
    /**
     * Read a small circle drawn on a key as that letter twice, the way Swype
     * taught. On, which is what the decoder always did: off is
     * [GlideBeam.Tuning.loopExtent] at zero, which stops the search looking
     * for loops at all. See [dwellFull].
     */
    val loopDouble: Boolean = GlideBeam.Tuning.DEFAULT.loopExtent > 0f,
    /**
     * Least path a curl has to hold, in key widths, before it counts as a
     * loop rather than the tremor of a slow pivot. Lower catches a smaller
     * circle and reads more curls as marks; higher asks for a circle drawn on
     * purpose. Does nothing while [loopDouble] is off. Range
     * [GlideLoopMinArcRange].
     */
    val loopMinArc: Float = GlideBeam.Tuning.DEFAULT.loopMinArc,
    /**
     * Widest a loop may be, in key widths, and still be a mark on one key.
     * This is what keeps the letters around it out of it: a word whose keys
     * are visited in a ring draws a real loop, and a limit above a key pitch
     * would read that as a doubled letter. Range [GlideLoopExtentRange].
     */
    val loopExtent: Float = GlideBeam.Tuning.DEFAULT.loopExtent,
    /**
     * How near a key's centre the loop's own centre has to sit for the loop
     * to be about that letter, in key widths. Range [GlideLoopRadiusRange].
     */
    val loopRadius: Float = GlideBeam.Tuning.DEFAULT.loopRadius,
    /**
     * Read a back-and-forth on a key — a scribble with no turn to it, which
     * is how some people mark a doubled letter — as that letter twice.
     *
     * Off, as it shipped: at the sloppy end of the corpus a slow pivot with
     * tremor on it looks the same, so this costs accuracy on strokes that
     * meant nothing by it. Switching it on takes the sliders below from
     * [GlideBeam.Tuning.WIGGLES_ON] rather than from the shipped zeroes,
     * which are the reading switched off.
     */
    val wiggleDouble: Boolean = GlideBeam.Tuning.DEFAULT.wiggleExtent > 0f,
    /**
     * Widest a wiggle may be, in key widths. Stays under one key pitch, or a
     * word drawn back and forth across two neighbouring keys reads as one
     * letter twice. Does nothing while [wiggleDouble] is off. Range
     * [GlideWiggleExtentRange].
     */
    val wiggleExtent: Float = GlideBeam.Tuning.WIGGLES_ON.wiggleExtent,
    /**
     * What a wiggle is worth as a doubled-letter mark, against a loop's 1.
     * Lower leaves a wiggle as a hint that frequency can still overrule;
     * higher makes it as good as a circle. Range [GlideWiggleWeightRange].
     */
    val wiggleWeight: Float = GlideBeam.Tuning.WIGGLES_ON.wiggleWeight,
    /**
     * Learn this user's swipe style from the swipes they keep, and read later
     * swipes by it (issue #52): where their finger actually lands on each
     * key, so a thumb that always cuts the far keys short stops paying for
     * it on every stroke; how they draw each word, so a word they have
     * swiped before is read against their own shape for it beside the ideal
     * one; and which word they take off the strip when a stroke is read
     * wrongly, so the same stroke reads their word first the next time.
     * Undoing a swipe takes its lesson back. On by default; off freezes what
     * is learned and reads the keys as drawn.
     */
    val learnSwipeStyle: Boolean = true,
    /**
     * How many ways of drawing one word [learnSwipeStyle] keeps apart
     * (#326). Three is what the store always kept; the ceiling is high on
     * purpose, so a user can let it grow and see in a backup how many a hand
     * really uses before the default is revisited. Lowering it hides the
     * least-used extras from the decoder at once and drops them only when the
     * word next learns a new way. Range [GlideShapesPerWordRange].
     */
    val shapesPerWord: Int = GlideShapeStore.DEFAULT_SHAPES_PER_WORD,
    /**
     * Offer the full search as a chip on the suggestion strip whenever the
     * caret lands inside a word a swipe wrote and whose path is still kept
     * (#135).
     *
     * The search itself is always available, from the held-word menu. This is
     * only whether it also comes forward by itself, which costs a strip slot
     * every time a swiped word is read back — so it is off until asked for.
     */
    val searchAllChip: Boolean = false,
    /**
     * Bumped by the gestures screen's "forget" so a running keyboard drops
     * its in-memory copies of the swipe-style stores — the contract of
     * [KeyboardSettings.lexiconVersion], on a counter of its own so
     * forgetting a swipe style never touches which words were learned.
     */
    val swipeStyleVersion: Int = 0,
)

/**
 * The glide decoder's weights as these settings ask for them.
 *
 * One place, rather than a named argument per weight at each call site: the
 * settings arrive together and the decoder takes them together, and a weight
 * wired in one call site and forgotten in the other is a setting that works
 * until the keyboard reloads its dictionaries. Everything not on this screen
 * stays at [GlideBeam.Tuning.DEFAULT].
 *
 * The two toggles are what turns their reading off: a loop or a wiggle is
 * switched off by an extent of zero, which is also how the decoder ships the
 * wiggle, so a switch is a clearer way to say it than a slider with an off
 * position at one end.
 */
fun GestureSettings.glideTuning(): GlideBeam.Tuning = GlideBeam.Tuning.DEFAULT.copy(
    startRadius = startRadius,
    endRadius = endRadius,
    nearRadius = nearRadius,
    vocabularyRank = vocabulary.rank,
    dwellFull = dwellFull,
    loopExtent = if (loopDouble) loopExtent else 0f,
    loopMinArc = loopMinArc,
    loopRadius = loopRadius,
    wiggleExtent = if (wiggleDouble) wiggleExtent else 0f,
    wiggleWeight = if (wiggleDouble) wiggleWeight else 0f,
)

/**
 * Which glyph each customisable icon draws, grouped into its own class rather
 * than sitting flat on [KeyboardSettings] because that class's primary
 * constructor is at the JVM's 255-argument ceiling (see [ToolbarBehavior]).
 * Both fields still persist under their own DataStore key.
 *
 * Resolution order is [overrides], then [activePackId], then the built-in
 * glyph — a single icon the user picked by hand outranks the pack they
 * installed, which outranks the app's own default. See
 * `com.wasimaster.wmkeyboard.core.icons.IconSlots` for the slot ids and
 * `ime/ui/IconResolver.kt` for the lookup itself.
 */
data class IconSettings(
    /**
     * The installed icon pack supplying icons for every slot the user hasn't
     * overridden individually. Blank means the built-in icons.
     */
    val activePackId: String = "",
    /**
     * Slot id → icon source, for slots the user changed one at a time.
     *
     * A source is `b:<name>` for one of the bundled Material icons (see
     * `BuiltinIcons`) or `p:<packId>` to take that slot from a specific
     * installed pack. An entry naming a pack or an icon that no longer exists
     * falls back to the default rather than drawing nothing.
     */
    val overrides: Map<String, String> = emptyMap(),
)

/**
 * Assorted layout & gesture behaviours layered on top of the base keyboard,
 * grouped into their own class rather than sitting flat on [KeyboardSettings]
 * because that class's primary constructor is at the JVM's 255-argument
 * ceiling (see [ToolbarBehavior]). Each field still persists under its own
 * DataStore key via the matching setter.
 */
data class LayoutBehaviorSettings(
    /**
     * Long-pressing the ?123 / symbols key opens the numeric keypad panel on
     * any field, instead of the long-press behaving like a plain tap. On by
     * default — the keypad is otherwise buried in the toolbox, and a held ?123
     * costs nothing when it is not wanted.
     */
    val symbolsLongPressNumpad: Boolean = true,
    /**
     * Holding the enter key offers the emoji panel, the way SwiftKey's enter
     * key does, for a layout whose bottom row has no emoji key of its own.
     *
     * It is an entry in the key's press and hold popup rather than a hold that
     * jumps straight to the panel, because the hold is already shared: a field
     * that declares Send/Go/Search puts the line break it displaces there. Both
     * stay reachable that way. The emoji entry goes first, so with hold to
     * select on a plain hold and release opens the panel — that is what
     * turning this on asks for — and the line break is one slide along.
     *
     * Off by default: it changes what an existing hold on enter does.
     */
    val enterLongPressEmoji: Boolean = false,
    /**
     * Swiping straight down on the spacebar dismisses the keyboard, the way a
     * downward flick on the toolbar can. Off by default so a stray vertical
     * drag never closes the keyboard mid-type.
     */
    val spaceSwipeDownHide: Boolean = false,
    /**
     * A short, quick swipe down on a key types its corner hint — the first of
     * its long-press characters — without waiting out the hold (issue #178).
     * The `1` on `q`, the `!` on the exclamation-mark key's shifted twin,
     * whatever the layout put there. Any key with long-press characters, on
     * any layer; the spacebar, backspace and the modifier keys keep their own
     * drags.
     *
     * Off by default. A glide that opens straight down off a key — a fast
     * "ed" — can read as a flick, and a board with glide typing on is a board
     * whose owner may glide short words. Duration, direction, length and
     * straightness all have to agree before a stroke is taken from the
     * decoder, and the setting's own text says what it costs.
     */
    val hintFlick: Boolean = false,
    /**
     * The upward twin of [hintFlick]: a short, quick swipe up on a letter
     * types its capital, or on a key with a shifted character of its own
     * ([com.wasimaster.wmkeyboard.core.layout.Key.shiftLabel]) that character,
     * without touching shift. What Gboard-patches calls up-flick uppercase.
     *
     * A key carrying an octopus word keeps its flick for the word. Off by
     * default for the reason [hintFlick] is: a fast glide that opens straight
     * up off a key ("de") can read as a flick.
     */
    val capitalFlick: Boolean = false,
    /**
     * For this long after a typed key, a tap on the 🌐 key is ignored, in ms
     * (0 = off). The globe sits between `?123`/comma and the spacebar, and a
     * thumb reaching for either mid-word lands on it often enough to switch
     * language in the middle of a sentence. A deliberate switch comes after a
     * pause; an accidental one comes straight out of typing. Holding the key
     * still opens the picker at any time. Off by default: nothing about the
     * key changes until someone asks for it.
     */
    val globeTypingGuardMs: Int = 0,
    /**
     * How round the docked keyboard's top corners are, in dp (0 = square, the
     * default). The corners are cut out of the whole board, so the app shows
     * through them, the way Gboard-patches' rounded panel does. The floating
     * panel and the television card have rounded corners of their own and are
     * left alone. See [BoardCornerRadiusRange].
     */
    val boardCornerTopDp: Int = 0,
    /** The same for the two bottom corners, which suit gesture navigation. */
    val boardCornerBottomDp: Int = 0,
    /** Which of the four corners [boardCornerTopDp] and [boardCornerBottomDp] round. All four by default. */
    val boardCorners: Set<BoardCorner> = BoardCorner.entries.toSet(),
    /**
     * Turn the spacebar cursor slide into a 2-D touchpad: a vertical drag moves
     * the caret up and down as well as left and right. Only applies while a
     * spacebar swipe slot is set to cursor control; when on it also claims the
     * downward direction, so it takes precedence over [spaceSwipeDownHide].
     * Off by default.
     */
    val spaceCursor2d: Boolean = false,
    /**
     * Characters the spacebar's long press offers in the alternates popup, in
     * order. Empty (the default) leaves the hold alone: it opens the language
     * picker when more than one input mode is on, and repeats spaces otherwise.
     *
     * Authoring keys here claims the hold outright, on every layer, because a
     * hold cannot mean two things at once (issue #57). The language picker is
     * still on the 🌐 key and on the spacebar swipe, and holding to repeat
     * spaces is what a second tap does.
     */
    val spaceHoldKeys: List<String> = emptyList(),
    /** What the resting spacebar label shows: language, layout, or both. */
    val spacebarDisplay: SpacebarDisplay = SpacebarDisplay.LANGUAGE,
    /**
     * Whether the language picker is a vertical list or a sideways carousel.
     * Defaults to the list, which is what shipped; see [LanguagePickerStyle].
     */
    val languagePickerStyle: LanguagePickerStyle = LanguagePickerStyle.LIST,
    /**
     * Whether a spacebar hold with more than four layouts on opens the full
     * picker ([languagePickerStyle]) rather than the inline preview. On is what
     * shipped. Off keeps the sideways preview at every ring length: it scrolls
     * and windows itself, so a long ring stays reachable from it.
     */
    val spaceHoldPickerForLongRing: Boolean = true,
    /**
     * Size multiplier for the small corner hint character on each key (the
     * first long-press alternate, shown when [KeyboardSettings.longPressHints]
     * is on). 1.0 keeps the default 10sp base.
     */
    val hintFontScale: Float = 1.0f,
    /**
     * How far the corner hint sits below the key's top edge, in dp (#208). 0
     * puts the glyph against the edge; larger values move it down toward the
     * label.
     */
    val hintOffsetDp: Int = 1,
    /**
     * On a transliterating layout (Avro), each key's corner hint shows the
     * script it is about to type rather than its long-press alternate: ক on
     * the `k`, কা on the `a` once a consonant is composing, ক্ক on the `k`
     * after one. The hints follow the composing buffer and the shift state, so
     * the roman grid reads as the Bengali it produces. Off by default: the
     * hints change on every keystroke, so they cost every key a redraw per
     * letter typed, and a phonetic typist mostly knows the scheme already.
     * [TransliterationHintMode] picks how much of the cluster it shows.
     */
    val transliterationHints: TransliterationHintMode = TransliterationHintMode.OFF,
    /**
     * When on, holding shift on the letters layer swaps the extra number row's
     * digits for the symbol layer's bracket/math fill row (`=\<>[]{}|~`), so
     * those symbols are reachable without leaving the letters. Only has an
     * effect while [KeyboardSettings.numberRow] is on. Off by default.
     */
    val numberRowShiftSymbols: Boolean = false,
    /**
     * Smart key-hit detection: while a word is being typed, the touch target of
     * each letter is nudged toward the letters most likely to come next (from
     * the dictionary), so a tap that lands just inside a neighbour's cell still
     * commits the intended letter. Only biases boundary taps and only on the
     * letters layer; deliberate presses well inside a key are untouched. On by
     * default.
     */
    val smartHitDetection: Boolean = true,
    /**
     * How hard autopilot ([smartHitDetection]) pulls a boundary tap toward a
     * likely letter, from 1 (barely) to 10 (as far as the reach cap allows).
     * 5 is the strength the feature shipped with.
     */
    val autopilotStrength: Int = 5,
    /**
     * Draw each favoured letter at the size its touch area has grown to, so the
     * effect of [autopilotStrength] is visible while you type. Off by default:
     * the feature is meant to be quiet, and keys that resize under the eye are
     * a distraction for most people.
     */
    val autopilotShowEffect: Boolean = false,
    /**
     * How much bigger than life [autopilotShowEffect] draws a favoured letter.
     * 1.0, the default, draws the touch area at its true size. Higher values
     * multiply the *growth* alone, so a letter that claimed nothing extra still
     * draws at its own size and only the difference is exaggerated.
     *
     * It moves nothing the finger is judged against. [autopilotOutline] ignores
     * it outright: an outline that lied about the boundary would defeat the
     * only thing it is for.
     */
    val autopilotVisualScale: Float = 1.0f,
    /**
     * Draw the exact boundary each favoured letter has claimed, for tuning
     * [autopilotStrength]. Off by default.
     */
    val autopilotOutline: Boolean = false,
    /**
     * Which digit glyphs the number row and numpad draw, and (per
     * [numeralCommitScope]) type — chosen per language, keyed by
     * [com.wasimaster.wmkeyboard.core.script.LanguageDef.id]. An absent language
     * is [NumeralSystem.AUTO]: it follows its own default (Arabic → ٠-٩,
     * Persian/Urdu → ۰-۹, Bengali → ০-৯, the Devanagari languages → ०-९,
     * everything else Latin). Read it through [numeralSystemFor].
     */
    val numeralSystemByLang: Map<String, NumeralSystem> = emptyMap(),
    /**
     * Where a non-Latin numeral system rewrites committed digits. Default
     * [NumeralCommitScope.TEXT_ONLY] keeps ASCII in numeric/phone/date/time
     * fields so those stay machine-parseable while typing native digits
     * elsewhere. Drawing is unaffected — the glyphs always show on the keys.
     * Global on purpose: it is about what fields tolerate, not about a script.
     */
    val numeralCommitScope: NumeralCommitScope = NumeralCommitScope.TEXT_ONLY,
    /**
     * Holding shift while pressing Enter types a real newline instead of firing
     * the field's editor action. The escape hatch for chat apps, where the
     * field declares Send and there is otherwise no way to put a line break in
     * a message without sending it.
     *
     * Only a shift the *user* armed counts — auto-capitalize arms the same
     * one-shot at the start of an empty message, and an empty chat box is
     * exactly where Enter still has to send. Caps lock is left out for the same
     * reason: it is about letter case, not about Enter.
     *
     * On by default. The fear that kept it off was a keyboard that quietly
     * stops sending messages, and the shift-the-user-armed rule above is what
     * answers it: the only shift that overrides is one the user put up on
     * purpose, one key before pressing Enter. Nobody does that by accident, and
     * every chat app on the platform reads Shift+Enter this way already.
     *
     * Turning it off leaves the line break reachable on the enter key's long
     * press, which is not gated on this and never sends.
     */
    val shiftEnterNewline: Boolean = true,
    /**
     * Whether the dedicated number row also shows while the symbols layer is up.
     * Only meaningful when [KeyboardSettings.numberRow] is on. On by default (the
     * long-standing behaviour); off keeps the digit row on the letters layer and
     * drops it from ?123.
     *
     * Off takes the digits away and nothing else: ?123 keeps the bracket and
     * maths row it is given in place of its own digit row, because those digits
     * are up on the letters layer either way. Letting the layer's digit row come
     * back instead made the option look like it deleted a row of symbols, which
     * is what it was reported as (issue #93).
     */
    val numberRowInSymbols: Boolean = true,
    /**
     * Height of the bottom row (space / enter), in dp, independent of the other
     * keys' [KeyboardSettings.keyHeightDp]. 0 means "follow the key height" — the
     * default, so the row is unchanged until asked. Raise it for a fatter,
     * easier-to-hit spacebar without growing the whole keyboard.
     */
    val bottomRowHeightDp: Int = 0,
    /**
     * Horizontal padding kept clear at the left edge of the keyboard, as a
     * fraction of its width (0 = none, the default; 0.15 = 15% shaved off that
     * side). Narrows the keys toward the centre for thumb reach without docking
     * to one side the way one-handed mode does. See [SidePadScaleRange].
     *
     * Paired with [sidePadRightScale] (issue #41). One symmetric slider became
     * two so a right-handed user can shave the left edge alone; builds that
     * stored the old single `side_pad_scale` seed both of these from it, so an
     * upgrade looks exactly the same until one of the two is moved.
     */
    val sidePadLeftScale: Float = 0f,
    /** The same at the right edge. See [sidePadLeftScale]. */
    val sidePadRightScale: Float = 0f,
    /**
     * Hold the split layout back until the screen is actually wide enough for
     * it: landscape, or a device unfolded past
     * [ScreenVariant.UNFOLDED_MIN_DP].
     *
     * [KeyboardSettings.splitKeyboard] is one global flag, so turning it on in
     * landscape left the keyboard split after rotating back to a portrait
     * phone, where a split layout is close to unusable. Off by default, which
     * keeps the flag meaning exactly what it always did.
     */
    val splitOnlyOnLargeScreens: Boolean = false,
    /**
     * How long, in ms, a second shift tap still counts as the double-tap that
     * turns on caps lock. Lower makes caps lock quicker but easier to trigger by
     * accident; higher makes a deliberate double-tap more forgiving. Default 350.
     * See [ShiftCapsLockMsRange].
     */
    val shiftCapsLockMs: Int = 350,
    /**
     * Populate every letter key's long-press popup with the full set of accented
     * variants for that letter (à á â ä ã å …), on top of whatever the layout
     * already lists. Latin letters only. Off by default: the built-in popups are
     * deliberately short, and the full set is a wall of glyphs most people never
     * want. See [LatinAccents].
     */
    val showAllPopupKeys: Boolean = false,
    /**
     * Add each letter key's shifted form to its long-press popup — `A` under
     * `a` — so a capital can be typed without arming shift.
     *
     * Asked for alongside the layer peek (issue #108): a drag off a layer's
     * `ABC` key types a letter without leaving that layer, and shift is on the
     * layer the finger is not on, so the popup is the only place a capital can
     * come from. It is a popup entry like any other, though, so it works under
     * an ordinary press and hold too.
     *
     * Appended, never prepended: with hold-to-select on, the first entry is
     * what a hold-and-release commits, and that has always been the layout's
     * own first alternate. Off by default — every letter key would otherwise
     * grow a popup it did not have.
     */
    val shiftedPopupKeys: Boolean = false,
    /**
     * The currency glyphs offered on the `$` key's long-press popup, in order.
     * Empty (the default) uses the built-in set (৳ € £ ¥ ₹ ₿). Lets a user put
     * their own currency first without editing a whole custom layout.
     */
    val currencyKeys: List<String> = emptyList(),
    /**
     * The Fancy Text style the fancy layout draws and types
     * (FancyStyles id — "bold", "fraktur", …). Written by the style strip
     * over the keys and by the language's settings page; ignored everywhere
     * outside the fancy layout.
     */
    val fancyStyleId: String = "bold",
    /**
     * The style the Fancy tool turns on with (a FancyStyles id), or null to
     * start from whatever style is already picked. Only the session is
     * restyled: the tool never overwrites the style the strip persisted, so
     * a pinned style is a way in rather than a new default.
     */
    val fancyToolStyleId: String? = null,
    /**
     * Keep Fancy Text in the language cycle after the Fancy tool turns it off.
     * Off (the default) takes the layout back out again, so the 🌐 key cycles
     * the languages the user actually reads. On leaves it there, for someone
     * who types fancy often enough to want it one swipe away.
     */
    val fancyToolKeepsLanguage: Boolean = false,
    /**
     * Turn Fancy Text off again when the keyboard closes, if the Fancy tool is
     * what turned it on. Fancy text is usually one nickname or one message, and
     * without this a user who forgets the tool types the next mail in Fraktur.
     * On by default for exactly that reason.
     */
    val fancyToolAutoOff: Boolean = true,
    /**
     * The secondary layout the Secondary layout tool shows (a `LayoutSpec.id`),
     * or null for the first one the user has. Null rather than a required pick
     * so the tool works the moment a first secondary layout exists; the page
     * only has to be visited once there are several.
     */
    val customLayoutToolId: String? = null,
    /**
     * Go back to the letters after typing one of [symbolsReturnChars] on the
     * symbols layer, so a full stop from ?123 does not leave the user on ?123.
     * The emoji panel has the same idea in
     * [EmojiSettings.closeAfterInsert]: one character is a detour, not a mode
     * change. Off by default, because the other half of the audience opens
     * ?123 to type a whole line of punctuation.
     */
    val symbolsReturnToLetters: Boolean = false,
    /**
     * The characters that send the symbols layer back to the letters, as one
     * string of single characters. Empty means [DefaultSymbolsReturnChars].
     * Digits are deliberately not in the default: typing "12" is exactly the
     * case where the user wants to stay.
     */
    val symbolsReturnChars: String = "",
    /**
     * The user has never touched the number-row toggle, so [applyDeviceForm] is
     * free to pick a default for the screen they are on.
     *
     * Derived from the *presence* of the DataStore key rather than its value,
     * which is the only durable record of "never chose" — every other read
     * collapses a missing key into the default with `?:` and loses it. Without
     * this a tablet user could not turn the digit row off: the overlay would put
     * it straight back, and the toggle would look broken.
     *
     * Not persisted itself. See [numberRowUntouched]'s use in `DeviceFormDefaults`.
     */
    val numberRowUntouched: Boolean = true,
    /**
     * The same, for the key-height slider — and for the number-row height, which
     * sits beside it: moving either one is a clear enough signal that the user is
     * sizing the board by hand that a second flag would only ever disagree with
     * this one at the wrong moment.
     */
    val keyHeightUntouched: Boolean = true,
    /**
     * The same again, for the board-width slider. Its own flag rather than a
     * ride on [keyHeightUntouched]: a television narrows the board without
     * touching its height (see `TelevisionDefaults`), so the two questions have
     * different answers there.
     */
    val keyboardWidthUntouched: Boolean = true,
    /**
     * The 🌐 key sits in the same place on every layout (#310): the slot the
     * built-in bottom row gives it, beside the spacebar, whatever the layout's
     * own bottom row says. A layout imported from Keyman can put it at the far
     * left, or after `?123`, and someone switching between three languages
     * then chases it around the row. On by default: the built-in layouts
     * already have it there, so they are left exactly as they were.
     */
    val globeInOnePlace: Boolean = true,
) {
    /** The characters that spring the symbols layer back, with the default applied. */
    fun symbolsReturnCharSet(): String =
        symbolsReturnChars.ifEmpty { DefaultSymbolsReturnChars }

    /** [langId]'s numeral system, [NumeralSystem.AUTO] when it has no entry. */
    fun numeralSystemFor(langId: String): NumeralSystem =
        numeralSystemByLang[langId] ?: NumeralSystem.AUTO
}

/**
 * Bounds for [LayoutBehaviorSettings.sidePadLeftScale] and
 * [LayoutBehaviorSettings.sidePadRightScale]; the settings sliders share them.
 * Each edge is capped on its own, and the pair together can therefore claim at
 * most 60% of the width — the keyboard's own minimum width takes it from there.
 */
val SidePadScaleRange = 0f..0.3f

/** Bounds for [LayoutBehaviorSettings.shiftCapsLockMs]; the settings slider shares them. */
val ShiftCapsLockMsRange = 150..600

/** Bounds for [LayoutBehaviorSettings.bottomRowHeightDp] when non-zero. */
val BottomRowHeightRange = 32..96

/** The built-in currency glyphs, used when [LayoutBehaviorSettings.currencyKeys] is empty. */
val DefaultCurrencyKeys = listOf("৳", "€", "£", "¥", "₹", "₿")

/**
 * The sentence punctuation that sends the symbols layer back to the letters,
 * used when [LayoutBehaviorSettings.symbolsReturnChars] is empty. Each of these
 * ends a sentence or a clause, so the next thing typed is almost always a word.
 */
const val DefaultSymbolsReturnChars = "!?.,;:"

/**
 * Accent variants per base Latin letter, merged into a key's long-press popup
 * when [LayoutBehaviorSettings.showAllPopupKeys] is on. Lowercase keys; the
 * runtime upper-cases them to match the key's shift state.
 */
val LatinAccents: Map<Char, List<String>> = mapOf(
    'a' to listOf("à", "á", "â", "ä", "ã", "å", "ā", "ą", "ǎ", "æ"),
    'c' to listOf("ç", "ć", "č", "ċ"),
    'd' to listOf("ð", "ď", "đ"),
    'e' to listOf("è", "é", "ê", "ë", "ē", "ė", "ę", "ě", "ə"),
    'g' to listOf("ğ", "ģ", "ġ"),
    'i' to listOf("ì", "í", "î", "ï", "ī", "į", "ı"),
    'l' to listOf("ł", "ĺ", "ľ", "ļ"),
    'n' to listOf("ñ", "ń", "ň", "ņ", "ŋ"),
    'o' to listOf("ò", "ó", "ô", "ö", "õ", "ø", "ō", "ő", "œ"),
    'r' to listOf("ř", "ŕ", "ŗ"),
    's' to listOf("ß", "ś", "š", "ş", "ș"),
    't' to listOf("ť", "ţ", "ț", "þ"),
    'u' to listOf("ù", "ú", "û", "ü", "ū", "ů", "ű", "ų"),
    'w' to listOf("ŵ"),
    'y' to listOf("ý", "ÿ", "ŷ"),
    'z' to listOf("ž", "ź", "ż"),
)

/**
 * How far detecting the field's language may shift suggestions and
 * autocorrect toward it. Maps to the engine's calibrated shift constants;
 * GENTLE re-orders the strip without dethroning the on-screen language,
 * AGGRESSIVE hands ranking and autocorrect over completely once the field's
 * words say so.
 */
enum class LanguageDetectionStrength { GENTLE, BALANCED, AGGRESSIVE }

/**
 * The optional items of the menu that opens when a word on the suggestion
 * strip is pressed and held (#99). "Edit" is not here because it is always
 * offered: it opens the word card, which carries every one of these actions
 * too, so hiding all of them still leaves everything reachable. [SYNONYMS]
 * is the one the card does not carry (#321): it replaces the word in the
 * text rather than editing it in the dictionary.
 */
enum class WordMenuItem { NEVER_SUGGEST, ADD, DELETE, SYNONYMS }

/**
 * What the word card's rank control changes (#99).
 *
 * [LEARNED_WEIGHT] edits the personal dictionary's count for the word, the
 * same number the dictionary screen's edit dialog shows: a word the keyboard
 * has not learned is added at the chosen weight, and weight 0 forgets it.
 * It cannot push a word below a list's own ranking, since the count only ever
 * adds. [RANK_OFFSET] keeps a separate up-or-down adjustment per word that
 * the engine applies on top of every source, so any word — one from a
 * downloaded list included — can be moved either way without touching what
 * the keyboard learned.
 *
 * [BOTH] shows the two controls one under the other, which is the default:
 * they answer different questions ("how well does the keyboard know this
 * word" and "where do I want it on the strip"), and the card is where a word
 * is edited without a trip to the personal dictionary (#138).
 */
enum class RankControl { LEARNED_WEIGHT, RANK_OFFSET, BOTH }

/** The order the Learn from text panel lists unknown words in (#174). */
enum class LearnFromTextSort { MOST_FREQUENT, TEXT_ORDER, ALPHABETICAL }

/**
 * Where a suggestion too long for its slot, even after shrinking, is cut.
 * [MIDDLE] keeps both ends of the word ("Punct…tion"), as Gboard does; [END]
 * keeps only its start ("Punctuat…").
 */
enum class SuggestionOverflow { MIDDLE, END }

/**
 * Suggestion-strip content options, grouped into their own object (see
 * [CameraSettings] for why the top-level class can't take more flat fields).
 * DataStore keys stay flat.
 */
data class SuggestionStripSettings(
    /**
     * Offer a row of common punctuation ( . , ? ! ' ) beside the word
     * candidates, so a full stop or comma is one tap away without a detour to
     * the symbols layout. Shown only while candidates are up; an emoji
     * prediction takes the tail instead when one is present.
     */
    val punctuation: Boolean = false,
    /**
     * The marks [punctuation] offers, in order, one per character.
     *
     * A list rather than a constant because the fixed `. , ? ! '` is an
     * English answer on a keyboard that ships 843 languages: a Bengali typist
     * wants the danda, a Spanish one the inverted marks, and neither could
     * reach them from here. Blank falls back to the shipped set, so emptying
     * the field cannot leave the row with nothing in it.
     */
    val punctuationChips: String = ".,?!'",
    /**
     * How many word candidates the strip shows at once.
     *
     * Three is the phone-width answer and the long-standing constant. The
     * slots split the strip evenly, so raising it on a narrow screen buys more
     * candidates at the cost of reading each one; a tablet or a landscape
     * phone has the room to spare.
     */
    val slotCount: Int = 3,
    /**
     * Multiplier on the suggestion text, and on the CJK candidate text with
     * it: both are the same row in different modes, so one number keeps them
     * agreeing.
     *
     * Key labels have scaled since the beginning and the words above them
     * never did, which left a low-vision user with large keys and 16 sp
     * suggestions they still could not read.
     */
    val textScale: Float = 1f,
    /**
     * Let the strip scroll sideways instead of squeezing every candidate into
     * an equal share of the width.
     *
     * With this on each word is drawn at its natural width, never shrunk or
     * condensed, and the row scrolls when they overrun the strip. A slot still
     * gets at least its equal share, so a set of short words fills the strip
     * exactly as it did before and only a long one pushes past the edge. It
     * is the answer for a narrow phone that wants five or six candidates
     * (issue #74): at fixed widths those slots were too tight to read.
     */
    val scrollable: Boolean = false,
    /**
     * The colour the strip draws its primary word in: the bold one autocorrect
     * puts in for a space (#90). ARGB; null follows the theme's suggestion text.
     */
    val primaryColor: Long? = null,
    /**
     * Breathing room on each side of a suggestion word inside its slot, in dp.
     *
     * Six matches what the strip always drew. Lower packs more of a long word
     * into a fixed-width slot before it has to shrink; higher keeps neighbours
     * from reading as one word once the strip scrolls. Lives here beside
     * [textScale] rather than in the appearance block because it draws the
     * same row.
     */
    val chipPadding: Int = 6,
    /**
     * How many times a word has to be typed before being learned protects it
     * from autocorrect.
     *
     * 1 is what the keyboard always did, and it means a typo committed once is
     * exempt from correction forever. Raising it asks for a second sighting
     * before the word is treated as deliberate. Suggestion ranking is
     * unaffected: a word below the threshold is still offered, just not
     * shielded. Lives here rather than beside the other autocorrect flags only
     * to stay under the settings class's JVM field ceiling.
     */
    val learnedWordMinCount: Int = 1,
    /**
     * How many times a word nothing recognises has to be typed *and left
     * alone* before it joins the personal dictionary at all.
     *
     * The keyboard used to learn a word the first time it was committed, which
     * meant one sloppy swipe put a misspelling in the dictionary — where it
     * was then offered as a suggestion and, worse, shielded from the
     * autocorrect that would have fixed it every time after. Counting
     * sightings instead means a real word the user keeps typing arrives within
     * a few uses, and a one-off slip never arrives at all.
     *
     * Only unknown words are counted. A word the dictionaries already know is
     * learned the moment it is typed, as before: there is nothing to protect
     * anyone from. A sighting only counts once the text has settled — see
     * `LearningBuffer`. 1 restores the old learn-immediately behaviour. Lives
     * here rather than beside the other learning flags only to stay under the
     * settings class's JVM field ceiling.
     */
    val newWordSightings: Int = 3,
    /**
     * Ask before learning an unknown word instead of counting sightings: the
     * strip offers an "add to dictionary?" chip the first time the word is
     * committed, and nothing is learned unless the user taps it.
     *
     * Off by default — a chip after a word you never think about again is a
     * chip in the way — but it is the exact behaviour some people want, and it
     * takes over from [newWordSightings] entirely when on.
     */
    val askBeforeLearning: Boolean = false,
    /**
     * Offer a correction that came close to firing as a chip on the strip,
     * instead of throwing it away.
     *
     * Autocorrect has to be nearly certain before it rewrites a word, because
     * being wrong changes what somebody wrote and they may not notice. That
     * left everything just short of certain going straight in the bin, even
     * when it was probably right. A chip costs a wrong guess nothing, so it
     * can be offered on much weaker evidence than a silent replacement.
     *
     * On by default: it only ever appears where nothing used to happen at all.
     * A correction the user has already rejected once is never offered.
     */
    val offerNearMissCorrections: Boolean = true,
    /**
     * After autocorrect rewrites a word, put a chip on the strip showing what
     * was actually typed. Tapping it puts that word back and retires the fix,
     * exactly as backspacing the correction away does.
     *
     * The mirror image of [offerNearMissCorrections]: that chip asks about a
     * correction that did not fire, this one takes back one that did. Backspace
     * already undoes a correction, but only in the instant right after it —
     * type one more letter and the only way back is to retype the word by
     * hand. The chip survives the rest of the sentence.
     *
     * Which corrections get a chip is [undoChipObviousness], not this switch;
     * on by default because it only ever appears where nothing used to.
     *
     * Lives here rather than beside the other autocorrect flags only to stay
     * under the settings class's JVM field ceiling, like [blockOffensiveWords].
     */
    val undoCorrectionChip: Boolean = true,
    /**
     * How obvious a correction may be and still earn an undo chip, 0 to 1.
     *
     * Most corrections need no chip. "teh" becoming "the" is a fix the user
     * asked for by typing badly, the engine was sure of it, and a chip after
     * every one of those is clutter that trains people to stop reading the
     * strip. What deserves the offer is the correction that was a guess, or
     * the one that changed the word past recognising — those are the ones
     * that go unnoticed until the message has been sent.
     *
     * So the chip is gated on how unremarkable the correction was
     * (`SuggestionEngine.CorrectionDecision.obviousness`, which falls with
     * both a thin confidence margin and a far-reaching edit), and this is the
     * bar it has to stay under. 0 shows a chip almost never, 1 shows one
     * after every correction.
     */
    val undoChipObviousness: Float = 0.5f,
    /**
     * Learn from the typos you fix by hand. A word you go back and change is
     * remembered as a pair — after two such fixes the keyboard makes the fix
     * for you, after one it offers to — and the slip behind it (a `3` where an
     * `e` was meant, a `b` where the space bar was) teaches autocorrect what
     * your fingers actually do.
     */
    val learnFromCorrections: Boolean = true,
    /**
     * Learn where your finger lands when tapping, key by key, from every word
     * you type and leave standing, and read later taps against keys moved to
     * match. The typing twin of the glide hand model.
     */
    val adaptToTaps: Boolean = true,
    /**
     * Bumped by the settings app when it edits or deletes the learned
     * corrections or the tap model, so a running keyboard reloads its copy —
     * its own signal, because the lexicon's also empties the learning buffer.
     */
    val correctionsVersion: Int = 0,
    /** Keep the suggestion strip as the default top bar even with nothing typed. */
    val suggestionsFirst: Boolean = false,
    /** Show the primary candidate in the middle slot (Gboard style) instead of the left. */
    val suggestionPrimaryCenter: Boolean = true,
    /**
     * Where a word that still overruns its slot after shrinking is cut (see
     * [SuggestionOverflow]). Has no effect on a scrolling strip, where every
     * word keeps its natural width.
     */
    val overflow: SuggestionOverflow = SuggestionOverflow.MIDDLE,
    /**
     * Keep potentially-offensive words out of the suggestion strip and never
     * autocorrect a neutral typo into one. On by default (as AOSP ships it); the
     * words can always still be typed and committed verbatim. Lives here rather
     * than beside the other autocorrect flags only to stay under the settings
     * class's JVM field ceiling.
     */
    val blockOffensiveWords: Boolean = true,
    /**
     * Context reranking of the suggestion strip's top candidates by learned
     * n-grams — pure on-device data, available in every channel.
     */
    val contextRerank: Boolean = true,
    /**
     * Type a space after a suggestion picked from the strip, so the next word
     * starts cleanly without reaching for the spacebar. On by default. Off
     * commits the word bare — for languages or fields where a trailing space is
     * wrong more often than right. A word resumed mid-sentence (one already
     * followed by a space) never gets a second one regardless.
     */
    val autoSpaceAfterSuggestion: Boolean = true,
    /**
     * Leave the word you have already typed out of the strip, so every slot
     * offers something new.
     *
     * Off by default, and deliberately so: that slot is how you *keep* what you
     * wrote. With it gone, a word the dictionary does not know has one fewer
     * way to survive autocorrect, and the strip stops showing you which of the
     * candidates is the literal thing you typed. Worth it for anyone who reads
     * the strip as three offers rather than as one offer and a confirmation.
     */
    val skipTypedWord: Boolean = false,
    /**
     * Expand shortcuts stored in Android's personal dictionary: if an entry has
     * a shortcut (e.g. "omw" → "on my way"), typing the shortcut offers the full
     * phrase as a suggestion. On by default: a shortcut is only ever there
     * because someone typed it into the platform dictionary UI meaning it to
     * expand. Independent of mirroring words *into* that dictionary; reads the
     * SHORTCUT column that UI fills in. See
     * [com.wasimaster.wmkeyboard.core.prediction.SystemUserDictionary].
     */
    val expandUserDictShortcuts: Boolean = true,
    /**
     * Treat every word in Android's personal dictionary as a known word: it
     * completes, it is never autocorrected away, and gliding it does not
     * raise the "add to dictionary?" chip (#45). On by default — a word the
     * user typed into the platform dictionary is a word they expect every
     * keyboard to know. Independent of mirroring words *into* that
     * dictionary ([KeyboardSettings.addWordsToSystemDictionary]). See
     * [com.wasimaster.wmkeyboard.core.prediction.SystemUserDictionary].
     */
    val useSystemDictionary: Boolean = true,
    /**
     * What the strip does when a triggered snippet has more than one thing to
     * say: several expansions of its own, or snippets linked to it.
     *
     * Chips only by default. Adding a second expansion to a snippet is asking
     * to choose between them, and a keyboard that picked one and rewrote the
     * text would be answering a question the user had just posed. A snippet
     * that wants the old behaviour back says so with its own
     * [com.wasimaster.wmkeyboard.core.snippets.MultiExpand].
     *
     * Lives here rather than on the settings class only to stay under that
     * class's JVM field ceiling; it is strip content either way.
     */
    val snippetMultiExpand: MultiExpandMode = MultiExpandMode.CHIPS_ONLY,
    /**
     * Show the system's smart replies ("On my way!") beside the word
     * candidates. They arrive down the same inline-suggestions API as
     * password-manager chips but from Android System Intelligence rather than
     * an autofill service, so they get their own toggle: wanting saved logins
     * in the strip says nothing about wanting the system to read the
     * conversation and propose answers to it. Android 11+; suppressed in
     * incognito along with the autofill lane. Lives here rather than beside
     * [SuggestionSourceSettings.inlineAutofill] only to stay under that class's JVM
     * field ceiling.
     */
    val systemSmartReplies: Boolean = true,
    /**
     * Adapt suggestions to where you're typing: chat-speak ("lol", "gonna")
     * ranks a little higher in messaging apps and a little lower in email
     * fields and clients. Ranking only — nothing is ever hidden or blocked —
     * and on by default.
     */
    val registerPriors: Boolean = true,
    /**
     * How strongly the typing rhythm of a word sways autocorrect, 0 (off,
     * the default) to 1. Fast, sloppy bursts make autocorrect fire more
     * eagerly; slow, deliberate typing — an unusual name, a foreign word —
     * makes it hold back.
     */
    val timingSignalStrength: Float = 0f,
    /**
     * Fix number-row slips: with the number row shown, a lone digit inside a
     * word ("as3", "tar8khe") reads as a tap that landed above the intended
     * letter, so the digit joins the composing word and autocorrect may swap
     * it for the letter below it ("ase", "tarikhe"). Only a same-length,
     * single-digit swap is ever trusted — "room3" is never shortened to
     * "room", and anything with two or more digits is left alone. Lives here
     * rather than beside the other autocorrect flags only to stay under the
     * settings class's JVM field ceiling.
     */
    val numberRowCorrections: Boolean = true,
    /**
     * The inverse, for a board with the number row off (#181): a word typed
     * entirely on keys whose corner hint is a digit ("qwe" on QWERTY) also
     * offers the number those hints spell ("123") as a strip candidate, in
     * the last slot, never the primary one and never autocorrected to. Long
     * runs are grouped the way the number chip groups them, under its style
     * setting, when that chip is on. Off by default: a single-letter word
     * is left alone either way, but "we", "it" and "up" all spell numbers,
     * and a slot is a slot. Nothing to offer while the number row is shown,
     * since its digits replace the hints. Same ceiling note as above.
     */
    val numberPrediction: Boolean = false,
    /**
     * Let autocorrect insert a missed space: "kortehobe" → "korte hobe" when
     * both halves are known words and no single-word fix is anywhere near,
     * including the fat-fingered-space reading ("amibtomake" → "ami tomake").
     * Backspace right after reverts the whole thing, exactly like a word
     * correction. Same ceiling note as above.
     */
    val autocorrectSplits: Boolean = true,
    /**
     * Languages that have turned the fixed-spelling map off, keyed by
     * [com.wasimaster.wmkeyboard.core.script.LanguageDef.id]. Absent means on,
     * so the map works out of the box and only an explicit opt-out disables it.
     *
     * The map is what makes "table" commit টেবিল instead of তাবলে, and "tmr"
     * তোমার instead of ত্ম্র. Someone who genuinely wants the letter-for-letter
     * reading — writing তাবলে on purpose — has no other way to get it, since
     * the map outranks every other source. Per language rather than global
     * because the lists are language-specific: switching Bengali's off says
     * nothing about any other script's.
     *
     * Lives here rather than beside the other language options only to stay
     * under the settings class's JVM field ceiling.
     */
    val spellingMapOffLangs: Set<String> = emptySet(),
    /**
     * Phonetic languages whose space bar commits the letter-for-letter reading
     * instead of a dictionary word that sounds like it ("asi" → আসি rather
     * than আছি). The sound-alikes stay in the strip. Stored as the switched-off
     * set, so a language nobody has touched keeps them on, as it always had.
     */
    val phoneticSiblingsOffLangs: Set<String> = emptySet(),
    /**
     * Languages whose suggestions come from the user's own imported word lists
     * alone: the bundled list and any downloaded one are dropped for them
     * (issue #28).
     *
     * Imported lists have always stacked on top of the shipped vocabulary
     * rather than standing in for it, so someone who brings their own 70k words
     * gets those *plus* everything the app already knew, with no way to say
     * which one they meant. This is that way. It is per language, and only
     * worth setting where there is a list to fall back on — the settings screen
     * offers it beside the lists themselves and says plainly when a language is
     * left with nothing.
     *
     * Empty by default, and stored as the exceptions rather than the rule so a
     * language nobody has touched needs no entry. Lives here rather than beside
     * the other dictionary options only to stay under the settings class's JVM
     * field ceiling.
     */
    val importedOnlyLangs: Set<String> = emptySet(),
    /**
     * Languages whose downloaded word-pair data (the n-gram pack) is switched
     * off: the pack stays on the device but predictions stop reading it. The
     * checkbox beside it on the language screen. Stored as the exceptions, like
     * [importedOnlyLangs], so a language nobody has touched needs no entry.
     */
    val wordPairsOffLangs: Set<String> = emptySet(),
    /**
     * Detect which language of the mix the current field is being written in
     * — from the words already in it — and lean suggestions and autocorrect
     * toward that language while it holds. Typing "ami tomake" on the English
     * keyboard makes it behave like the Banglish one, and "how are you"
     * swings it straight back. Per field, never persisted, and inert unless
     * the language has secondary suggestion languages configured. Lives here
     * rather than beside the other language options only to stay under the
     * settings class's JVM field ceiling.
     */
    val languageDetection: Boolean = true,
    /** How far the detected language may take over; see [LanguageDetectionStrength]. */
    val languageDetectionStrength: LanguageDetectionStrength = LanguageDetectionStrength.BALANCED,
    /**
     * Start each field's language detection from the language the user usually
     * writes in the app it belongs to (a small, per-app prior; see
     * `AppLanguageMix`). Off keeps detection reading the field alone.
     */
    val languageDetectionByApp: Boolean = true,
    /**
     * The languages whose phonetic layout (Avro, Hindi phonetic) commits a
     * buffer that reads as an English word, and not as one of the layout's
     * own, in Latin letters — `hello` stays hello where it used to come out
     * হ্যালো. Words both languages have follow the language the field is being
     * written in (see `PhoneticScriptVerdict`). Only does anything with English
     * among that language's secondary suggestion languages, which is also the
     * only time its row is shown. Per language because its row lives on the
     * language's own screen, and a switch on Bangla's screen that also changed
     * Hindi would be one nobody could find again. Empty: English is only ever
     * offered on the strip.
     */
    val phoneticEnglishLangs: Set<String> = emptySet(),
    /**
     * Whether the strip carries the small switch that turns the above on and
     * off for the language being typed. On by default, because the moment the
     * switch is wanted is the middle of a word that came out in the wrong
     * script; off for whoever never wants it off and would rather have the
     * room for a word.
     */
    val phoneticEnglishSwitch: Boolean = true,
    /**
     * The languages whose phonetic layout keeps the strip's first two chips
     * in place: the word as typed in Latin letters on the left, the rules'
     * reading of it (Avro's letter-for-letter Bengali) beside it, and the
     * suggestions after them. Off by default: the ordinary strip leads with
     * whatever a space would commit, and moving that is a change of habit
     * nobody should get without asking. A space commits the same word either
     * way. Per language, on the language's own screen, like
     * [phoneticEnglishLangs].
     */
    val phoneticFixedStripLangs: Set<String> = emptySet(),
    /**
     * What fills a fixed phonetic strip after its two chips, per language;
     * a language with no entry gets [PhoneticStripSource.SMART]. Read it
     * through [phoneticStripSourceFor].
     */
    val phoneticStripSources: Map<String, PhoneticStripSource> = emptyMap(),
    /**
     * Which optional items the held-word menu shows (#99). An item missing
     * from the set is never drawn; "Edit" is drawn regardless. All three by
     * default: the menu is contextual (add only while typing an unlearned
     * word, delete only for a word the keyboard can forget), so it is rarely
     * more than two items long. Lives here rather than on the top-level class
     * only to stay under its JVM field ceiling.
     */
    val wordMenuItems: Set<WordMenuItem> = WordMenuItem.entries.toSet(),
    /**
     * Where the held-word menu's Synonyms looks (#321), in the order it asks:
     * each source is asked only when the ones before it had no synonyms for
     * the word or could not be reached. A source switched off stays in the
     * list, in its place, so switching it back on does not lose the order.
     */
    val synonymSources: List<SynonymSourceChoice> = SynonymSources.DEFAULT,
    /** What the word card's rank controls edit; see [RankControl]. */
    val rankControl: RankControl = RankControl.BOTH,
    /**
     * Whether the strip's Delete takes a word out of the user's imported word
     * lists themselves (#190), rewriting the files, or leaves the lists alone
     * and blacklists the word instead. On by default: the lists are the
     * user's own, and a deleted word that comes back after "Suggest again"
     * reads as the delete not having worked. Off keeps a list intact for
     * someone who curates it elsewhere and re-imports.
     */
    val deleteEditsImportedLists: Boolean = true,
    /**
     * The order the Learn from text panel lists the words it found (#174),
     * picked from the panel itself and remembered for the next scan.
     */
    val learnFromTextSort: LearnFromTextSort = LearnFromTextSort.MOST_FREQUENT,
    /**
     * Whether Add in the Learn from text panel also teaches the word pairs
     * the text holds, the way typing them would have. The panel's Word pairs
     * chip; on by default.
     */
    val learnFromTextPairs: Boolean = true,
) {
    /** Whether the fixed-spelling map applies to [langId]. */
    fun spellingMapEnabledFor(langId: String): Boolean = langId !in spellingMapOffLangs

    /** Whether a space on [langId]'s phonetic layout may commit a sound-alike dictionary word. */
    fun phoneticSiblingsEnabledFor(langId: String): Boolean = langId !in phoneticSiblingsOffLangs

    /** Whether [langId]'s phonetic layout commits English words as English; null is no phonetic layout. */
    fun phoneticEnglishFor(langId: String?): Boolean = langId != null && langId in phoneticEnglishLangs

    /** What fills [langId]'s fixed phonetic strip after its two chips. */
    fun phoneticStripSourceFor(langId: String): PhoneticStripSource =
        phoneticStripSources[langId] ?: PhoneticStripSource.SMART

    /**
     * [langId]'s fixed-strip source when its phonetic layout keeps the first
     * two chips in place, or null for the ordinary strip (and for no phonetic
     * layout at all).
     */
    fun phoneticFixedStripFor(langId: String?): PhoneticStripSource? =
        langId?.takeIf { it in phoneticFixedStripLangs }?.let(::phoneticStripSourceFor)

    /**
     * Whether [langId] still reads the bundled and downloaded dictionaries, as
     * opposed to the user's imported lists alone. See [importedOnlyLangs].
     */
    fun shippedDictionaryEnabledFor(langId: String): Boolean = langId !in importedOnlyLangs

    /** Whether predictions read [langId]'s downloaded word-pair data. */
    fun wordPairsEnabledFor(langId: String): Boolean = langId !in wordPairsOffLangs
}

/**
 * DataStore-backed settings. Every option on the settings screens flows
 * through here; the IME service collects [settings] and re-renders live.
 */
/** Serializes the secondary-language map to a compact `primary=s1,s2;...` string. */
private fun encodeSecondaryLanguages(map: Map<String, List<String>>): String =
    map.entries
        .filter { it.value.isNotEmpty() }
        .joinToString(";") { (primary, secs) -> "$primary=${secs.joinToString(",")}" }

private fun decodeSecondaryLanguages(raw: String): Map<String, List<String>> =
    raw.split(';')
        .filter { it.isNotEmpty() }
        .mapNotNull { entry ->
            val eq = entry.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val secs = entry.substring(eq + 1).split(',').filter { it.isNotEmpty() }
            if (secs.isEmpty()) null else entry.substring(0, eq) to secs
        }
        .toMap()

/** Serializes the per-app layout map to a compact `pkg=layoutId;...` string. */
private fun encodePerAppLayouts(map: Map<String, String>): String =
    map.entries
        .filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
        .joinToString(";") { (pkg, layoutId) -> "$pkg=$layoutId" }

private fun decodePerAppLayouts(raw: String): Map<String, String> =
    raw.split(';')
        .filter { it.isNotEmpty() }
        .mapNotNull { entry ->
            val eq = entry.indexOf('=')
            if (eq <= 0 || eq == entry.length - 1) return@mapNotNull null
            entry.substring(0, eq) to entry.substring(eq + 1)
        }
        .toMap()

/** Serializes the per-language Whisper model map to a compact `lang=modelId;...` string. */
private fun encodeWhisperModelByLang(map: Map<String, String>): String =
    map.entries
        .filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
        .joinToString(";") { (language, modelId) -> "$language=$modelId" }

private fun decodeWhisperModelByLang(raw: String): Map<String, String> =
    raw.split(';')
        .filter { it.isNotEmpty() }
        .mapNotNull { entry ->
            val eq = entry.indexOf('=')
            if (eq <= 0 || eq == entry.length - 1) return@mapNotNull null
            entry.substring(0, eq) to entry.substring(eq + 1)
        }
        .toMap()

/** Serializes the per-language numeral map to a compact `lang=SYSTEM;...` string. */
private fun encodeNumeralSystems(map: Map<String, NumeralSystem>): String =
    map.entries
        .filter { it.key.isNotEmpty() && it.value != NumeralSystem.AUTO }
        .joinToString(";") { (language, system) -> "$language=${system.name}" }

/**
 * The octopus's allowed candidate kinds, newline-separated like every other
 * stored list. An empty stored value is a real answer — the user unticked
 * everything, which is a slower way of turning the feature off — so it is kept
 * rather than falling back to the default set.
 */
private fun decodeOctopusKinds(raw: String): Set<OctopusKind> =
    raw.split('\n')
        .filter { it.isNotEmpty() }
        .mapNotNull { runCatching { OctopusKind.valueOf(it) }.getOrNull() }
        .toSet()

private fun decodeNumeralSystems(raw: String): Map<String, NumeralSystem> =
    raw.split(';')
        .filter { it.isNotEmpty() }
        .mapNotNull { entry ->
            val eq = entry.indexOf('=')
            if (eq <= 0 || eq == entry.length - 1) return@mapNotNull null
            val system = runCatching {
                NumeralSystem.valueOf(entry.substring(eq + 1))
            }.getOrNull() ?: return@mapNotNull null
            entry.substring(0, eq) to system
        }
        .toMap()

private val endpointJson = Json { ignoreUnknownKeys = true }
private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())
private val repoMapSerializer = MapSerializer(String.serializer(), stringMapSerializer)

/** Service addresses as a JSON object: an address carries `;` and `=` too often for the compact form below. */
private fun encodeEndpointMap(map: Map<String, String>): String = endpointJson.encodeToString(stringMapSerializer, map)

private fun decodeEndpointMap(raw: String): Map<String, String> =
    runCatching { endpointJson.decodeFromString(stringMapSerializer, raw) }.getOrDefault(emptyMap())

private fun encodeRepoMap(map: Map<String, RepoLocation>): String =
    endpointJson.encodeToString(repoMapSerializer, map.mapValues { it.value.toFields() })

/** An entry naming a forge this build does not know is dropped, which means its default. */
private fun decodeRepoMap(raw: String): Map<String, RepoLocation> =
    runCatching { endpointJson.decodeFromString(repoMapSerializer, raw) }.getOrDefault(emptyMap())
        .mapNotNull { (id, fields) -> repoLocationFromFields(fields)?.let { id to it } }
        .toMap()

private val emojiOrderSerializer =
    MapSerializer(String.serializer(), ListSerializer(String.serializer()))

/** The per-category emoji order as a JSON object of category id to emoji list. */
private fun encodeEmojiOrder(map: Map<String, List<String>>): String =
    endpointJson.encodeToString(emojiOrderSerializer, map)

/**
 * A malformed blob reads as "no custom order", which puts every category back
 * in catalog order. That is the right failure: the orders here are a
 * preference laid over a catalog that is itself intact, so losing one costs
 * an arrangement, never an emoji.
 */
private fun decodeEmojiOrder(raw: String?): Map<String, List<String>> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching { endpointJson.decodeFromString(emojiOrderSerializer, raw) }
        .getOrDefault(emptyMap())
        .mapValues { (_, order) -> order.filter { it.isNotEmpty() }.distinct() }
        .filterValues { it.isNotEmpty() }
}

/** Serializes the per-script font map to a compact `SCRIPT=fontId;...` string. */
private fun encodeScriptFontIds(map: Map<String, String>): String =
    map.entries
        .filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
        .joinToString(";") { (script, fontId) -> "$script=$fontId" }

private fun decodeScriptFontIds(raw: String): Map<String, String> =
    raw.split(';')
        .filter { it.isNotEmpty() }
        .mapNotNull { entry ->
            val eq = entry.indexOf('=')
            if (eq <= 0 || eq == entry.length - 1) return@mapNotNull null
            entry.substring(0, eq) to entry.substring(eq + 1)
        }
        .toMap()

class SettingsRepository(private val context: Context) {

    /**
     * The device-protected copy of these settings, and the only one readable
     * during direct boot. See [LockedSettings] for what it does and does not
     * carry.
     */
    private val locked = LockedSettings(context)

    /**
     * Whether credential-encrypted storage is readable. Starts as whatever the
     * platform says at construction and only ever goes true — via
     * [onUserUnlocked], which the IME calls when the platform broadcasts the
     * unlock. Every read and write below routes on it, so a single flip moves
     * the whole repository from the mirror back to the real store.
     */
    private val unlocked = MutableStateFlow(DirectBoot.isUserUnlocked(context))

    companion object {
        private val Context.dataStore by preferencesDataStore(name = "keyboard_settings")

        // input_mode and enabled_modes are kept as the compatibility mirror of
        // the two keys below: they are written alongside, never read except by
        // an install that predates the layout registry.
        private val INPUT_MODE = stringPreferencesKey("input_mode")
        private val ENABLED_MODES = stringPreferencesKey("enabled_modes")
        private val ACTIVE_LAYOUT_ID = stringPreferencesKey("active_layout_id")
        private val ENABLED_LAYOUT_IDS = stringPreferencesKey("enabled_layout_ids")
        private val RECENT_LAYOUT_IDS = stringPreferencesKey("recent_layout_ids")
        private val CUSTOM_LAYOUTS = stringPreferencesKey("custom_layouts")
        private val SECONDARY_LANGUAGES = stringPreferencesKey("secondary_languages")
        private val AUTO_PAIR_ROMANIZED_DONE =
            booleanPreferencesKey("auto_pair_romanized_done")
        private val RAW_CLIPBOARD_SHORTCUTS = booleanPreferencesKey("raw_clipboard_shortcuts")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEYBOARD_THEME_ID = stringPreferencesKey("keyboard_theme_id")
        private val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        private val AUTO_THEME_ENABLED = booleanPreferencesKey("auto_theme_enabled")
        private val AUTO_THEME_LIGHT_ID = stringPreferencesKey("auto_theme_light_id")
        private val AUTO_THEME_DARK_ID = stringPreferencesKey("auto_theme_dark_id")
        private val AUTO_THEME_TRIGGER = stringPreferencesKey("auto_theme_trigger")
        private val AUTO_THEME_DAY_START = intPreferencesKey("auto_theme_day_start")
        private val AUTO_THEME_NIGHT_START = intPreferencesKey("auto_theme_night_start")
        private val AUTO_THEME_LIGHT_RANDOM = booleanPreferencesKey("auto_theme_light_random")
        private val AUTO_THEME_DARK_RANDOM = booleanPreferencesKey("auto_theme_dark_random")
        private val AUTO_THEME_LIGHT_POOL = stringSetPreferencesKey("auto_theme_light_pool")
        private val AUTO_THEME_DARK_POOL = stringSetPreferencesKey("auto_theme_dark_pool")
        private val AUTO_THEME_SHUFFLE_INTERVAL =
            stringPreferencesKey("auto_theme_shuffle_interval")
        private val AUTO_THEME_SHUFFLE_LIGHT_ID =
            stringPreferencesKey("auto_theme_shuffle_light_id")
        private val AUTO_THEME_SHUFFLE_DARK_ID = stringPreferencesKey("auto_theme_shuffle_dark_id")
        private val AUTO_THEME_SHUFFLED_AT = longPreferencesKey("auto_theme_shuffled_at")
        private val AUTO_THEME_SHUFFLED_AT_ELAPSED =
            longPreferencesKey("auto_theme_shuffled_at_elapsed")
        private val PHOTO_UNSPLASH_KEY = stringPreferencesKey("photo_unsplash_key")
        private val PHOTO_PEXELS_KEY = stringPreferencesKey("photo_pexels_key")
        private val PHOTO_ROTATE_ENABLED = booleanPreferencesKey("photo_rotate_enabled")
        private val PHOTO_ROTATE_INTERVAL = stringPreferencesKey("photo_rotate_interval")
        private val PHOTO_ROTATE_SCOPE = stringPreferencesKey("photo_rotate_scope")
        private val PHOTO_ROTATE_SCOPE_THEMES = stringSetPreferencesKey("photo_rotate_scope_themes")
        private val PHOTO_ROTATE_SOURCES = stringSetPreferencesKey("photo_rotate_sources")
        private val PHOTO_ROTATE_TOPICS = stringPreferencesKey("photo_rotate_topics")
        private val PHOTO_ROTATE_QUERIES = stringPreferencesKey("photo_rotate_queries")
        private val PHOTO_LANDSCAPE_ONLY = booleanPreferencesKey("photo_landscape_only")
        private val PHOTO_SAFE_SEARCH = booleanPreferencesKey("photo_safe_search")
        private val PHOTO_FETCH_ON_METERED = booleanPreferencesKey("photo_fetch_on_metered")
        private val PHOTO_POOL_TARGET = intPreferencesKey("photo_pool_target")
        private val PHOTO_KEY_OPACITY = floatPreferencesKey("photo_key_opacity")
        private val PHOTO_POOL_BUDGET_MB = intPreferencesKey("photo_pool_budget_mb")
        private val PHOTO_SEED_PALETTE = booleanPreferencesKey("photo_seed_palette")
        private val PHOTO_READABILITY_GUARD = booleanPreferencesKey("photo_readability_guard")

        /**
         * Which photo each rotating theme is showing, as JSON keyed by theme id.
         * Deliberately not part of [KeyboardSettings]: a rotation would then
         * re-emit the whole settings object, and losing this one key costs the
         * current photo rather than anything the user made.
         */
        private val PHOTO_ROTATION_STATE = stringPreferencesKey("photo_rotation_state")
        private val KEY_HEIGHT = intPreferencesKey("key_height")
        private val NUMBER_ROW_HEIGHT = intPreferencesKey("number_row_height")
        private val BOTTOM_PADDING = intPreferencesKey("bottom_padding")
        private val SPLIT_KEYBOARD = booleanPreferencesKey("split_keyboard")
        private val SPLIT_GAP_PERCENT = intPreferencesKey("split_gap_percent")
        private val FLOATING_KEYBOARD = booleanPreferencesKey("floating_keyboard")
        private val FLOATING_WIDTH = intPreferencesKey("floating_width")
        private val FLOATING_HEIGHT_SCALE = floatPreferencesKey("floating_height_scale")
        private val FLOATING_X = floatPreferencesKey("floating_x")
        private val FLOATING_Y = floatPreferencesKey("floating_y")
        private val PERSISTENT_KEYBOARD = booleanPreferencesKey("persistent_keyboard")
        private val KEYBOARD_WIDTH_PERCENT = intPreferencesKey("keyboard_width_percent")
        private val KEYBOARD_ALIGNMENT = stringPreferencesKey("keyboard_alignment")
        private val KEY_CORNER_RADIUS = intPreferencesKey("key_corner_radius")
        private val FONT_SCALE = floatPreferencesKey("font_scale")

        // Per-variant sizing overrides. The keys are derived from the base
        // names rather than spelled out, so the four screen shapes times six
        // settings stay in step with each other by construction.
        private fun variantKey(base: String, variant: ScreenVariant) = "${base}_${variant.suffix}"

        private fun keyHeightKey(v: ScreenVariant) = intPreferencesKey(variantKey("key_height", v))
        private fun numberRowHeightKey(v: ScreenVariant) =
            intPreferencesKey(variantKey("number_row_height", v))
        private fun bottomPaddingKey(v: ScreenVariant) =
            intPreferencesKey(variantKey("bottom_padding", v))
        private fun widthPercentKey(v: ScreenVariant) =
            intPreferencesKey(variantKey("keyboard_width_percent", v))
        private fun alignmentKey(v: ScreenVariant) =
            stringPreferencesKey(variantKey("keyboard_alignment", v))
        private fun fontScaleKey(v: ScreenVariant) =
            floatPreferencesKey(variantKey("font_scale", v))
        private fun keyboardScaleKey(v: ScreenVariant) =
            floatPreferencesKey(variantKey("keyboard_scale", v))
        private fun keyGapScaleKey(v: ScreenVariant) =
            floatPreferencesKey(variantKey("key_gap_scale", v))
        // Legacy, read-only: the symmetric per-variant pad builds before issue
        // #41 wrote. It seeds both halves below when neither has been written.
        private fun sidePadScaleKey(v: ScreenVariant) =
            floatPreferencesKey(variantKey("side_pad_scale", v))
        private fun sidePadLeftScaleKey(v: ScreenVariant) =
            floatPreferencesKey(variantKey("side_pad_left_scale", v))
        private fun sidePadRightScaleKey(v: ScreenVariant) =
            floatPreferencesKey(variantKey("side_pad_right_scale", v))
        private fun bottomRowHeightKey(v: ScreenVariant) =
            intPreferencesKey(variantKey("bottom_row_height", v))
        private fun variantNumberRowKey(v: ScreenVariant) =
            booleanPreferencesKey(variantKey("number_row", v))
        private val KEY_GAP_SCALE = floatPreferencesKey("key_gap_scale")
        private val KEY_FONT_ID = stringPreferencesKey("key_font_id")
        private val CUSTOM_FONT_NAME = stringPreferencesKey("custom_font_name")
        private val SCRIPT_FONT_IDS = stringPreferencesKey("script_font_ids")
        private val CUSTOM_SCRIPT_FONT_NAMES = stringPreferencesKey("custom_script_font_names")

        /**
         * Bengali's own font keys, from when it was the one script with a picker
         * of its own. It now rides [SCRIPT_FONT_IDS] like every other script, so
         * these are read once to carry an existing choice across and never
         * written again.
         */
        private val BENGALI_FONT_ID = stringPreferencesKey("bengali_font_id")
        private val CUSTOM_BENGALI_FONT_NAME = stringPreferencesKey("custom_bengali_font_name")
        private const val BENGALI_SCRIPT = "BENGALI"

        /**
         * The automatic face, mirroring `KeyboardFonts.DEFAULT_ID`. Repeated
         * rather than imported because that object lives in the IME module,
         * which this one is below.
         */
        private const val DEFAULT_FONT_ID = "default"
        private val LEXICON_VERSION = intPreferencesKey("lexicon_version")
        private val CUSTOM_DICT_VERSION = intPreferencesKey("custom_dict_version")
        /**
         * Retired: the language-download confirmation, now one of the answers
         * [DS_DOWNLOADS] can hold. Still read, once, so an existing "never ask"
         * survives the move (see `loadDataSaver`).
         */
        private val CONFIRM_METERED_DOWNLOADS =
            booleanPreferencesKey("confirm_metered_downloads")
        private val AUTO_PAIR_ROMANIZED = booleanPreferencesKey("auto_pair_romanized")
        private val MORSE_COMMIT_MS = intPreferencesKey("morse_commit_ms")
        private val AUTO_DOWNLOAD_LANGUAGE_DATA =
            booleanPreferencesKey("auto_download_language_data")
        private val EMOJI_FONT = stringPreferencesKey("emoji_font")
        private val EMOJI_FONT_INSTALLED_ID = stringPreferencesKey("emoji_font_installed_id")
        private val AUTO_APOSTROPHE = booleanPreferencesKey("auto_apostrophe")
        private val HAPTIC = booleanPreferencesKey("haptic")
        private val HAPTIC_STRENGTH = intPreferencesKey("haptic_strength")
        private val HAPTIC_AMPLITUDE = intPreferencesKey("haptic_amplitude")
        private val HAPTIC_STYLE = stringPreferencesKey("haptic_style")
        private val HAPTIC_ON_LONG_PRESS = booleanPreferencesKey("haptic_on_long_press")
        private val HAPTIC_ON_LONG_PRESS_RELEASE = booleanPreferencesKey("haptic_on_long_press_release")
        private val FEEDBACK_VIBRATE_SPACE = booleanPreferencesKey("feedback_vibrate_space")
        private val FEEDBACK_VIBRATE_DELETE_SWIPE = booleanPreferencesKey("feedback_vibrate_delete_swipe")
        private val FEEDBACK_VIBRATE_REPEAT = booleanPreferencesKey("feedback_vibrate_repeat")
        private val FEEDBACK_SOUND_REPEAT = booleanPreferencesKey("feedback_sound_repeat")
        private val FEEDBACK_RESPECT_SYSTEM_TOUCH =
            booleanPreferencesKey("feedback_respect_system_touch")
        private val FEEDBACK_TOAST_ON_COPY = booleanPreferencesKey("feedback_toast_on_copy")
        private val FEEDBACK_HAPTICS_RESPECT_DND = booleanPreferencesKey("feedback_haptics_respect_dnd")
        private val KEY_SOUND = booleanPreferencesKey("key_sound")
        private val KEY_POPUP = booleanPreferencesKey("key_popup")
        private val KEY_POPUP_MIN_DURATION = intPreferencesKey("key_popup_min_duration")
        private val KEY_POPUP_MAX_DURATION = intPreferencesKey("key_popup_max_duration")
        private val KEY_POPUP_ON_KEY = booleanPreferencesKey("key_popup_on_key")
        private val KEY_POPUP_IN_NUMERIC = booleanPreferencesKey("key_popup_in_numeric_fields")
        private val POPUP_FONT_SCALE = floatPreferencesKey("popup_font_scale")
        private val KEY_POPUP_HEIGHT = intPreferencesKey("key_popup_height")
        private val KEY_POPUP_FLOATING_HEIGHT = intPreferencesKey("key_popup_floating_height")
        private val KEY_POPUP_OFFSET_Y = intPreferencesKey("key_popup_offset_y")
        private val KEY_POPUP_OFFSET_X = intPreferencesKey("key_popup_offset_x")
        private val KEY_POPUP_BACKGROUND = longPreferencesKey("key_popup_background")
        private val KEY_POPUP_TEXT_COLOR = longPreferencesKey("key_popup_text_color")
        private val KEY_POPUP_RADIUS = intPreferencesKey("key_popup_radius")
        private val KEY_POPUP_SHAPE = stringPreferencesKey("key_popup_shape")
        private val ALTERNATES_FONT_SCALE = floatPreferencesKey("alternates_font_scale")
        private val ALTERNATES_PADDING = intPreferencesKey("alternates_padding")
        private val ALTERNATES_COLUMNS = intPreferencesKey("alternates_columns")
        private val ALTERNATES_NEAREST_FIRST = booleanPreferencesKey("alternates_nearest_first")
        private val ALTERNATES_HOLD_TO_SELECT = booleanPreferencesKey("alternates_hold_to_select")
        private val COLOR_VISION_FILTER = stringPreferencesKey("color_vision_filter")
        private val HIGH_CONTRAST_KEYS = booleanPreferencesKey("high_contrast_keys")
        private val KEY_OUTLINES = booleanPreferencesKey("key_outlines")
        private val BOLD_KEY_LABELS = booleanPreferencesKey("bold_key_labels")
        private val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        private val SCREEN_READER_MODE = stringPreferencesKey("screen_reader_mode")
        private val KEY_DEBOUNCE_MS = intPreferencesKey("key_debounce_ms")
        private val NUMBER_ROW = booleanPreferencesKey("number_row")
        private val AUTOCORRECT = booleanPreferencesKey("autocorrect")
        private val AUTOCORRECT_CONFIDENCE = floatPreferencesKey("autocorrect_confidence")
        private val AUTOCORRECT_ADAPTIVE = booleanPreferencesKey("autocorrect_adaptive")
        private val AUTOCORRECT_UNDO_MEMORY =
            stringPreferencesKey("autocorrect_undo_memory")
        private val REVERT_AUTOCORRECT_ON_BACKSPACE =
            booleanPreferencesKey("revert_autocorrect_on_backspace")
        private val AUTOCORRECT_SKIP_ALL_CAPS =
            booleanPreferencesKey("autocorrect_skip_all_caps")
        private val AUTO_CAPITALIZE = booleanPreferencesKey("auto_capitalize")
        private val DOUBLE_SPACE_PERIOD = booleanPreferencesKey("double_space_period")
        private val DOUBLE_SPACE_TAB = booleanPreferencesKey("double_space_tab")
        private val AUTO_SPACE_AFTER_PUNCTUATION =
            booleanPreferencesKey("auto_space_after_punctuation")
        private val HUG_PUNCTUATION = booleanPreferencesKey("hug_punctuation")
        private val HUG_PUNCTUATION_MARKS = stringPreferencesKey("hug_punctuation_marks")
        private val LANGUAGE_PUNCTUATION_SPACING =
            booleanPreferencesKey("language_punctuation_spacing")
        private val WRAP_SELECTION_WITH_PAIR = booleanPreferencesKey("wrap_selection_with_pair")
        private val AUTO_CLOSE_BRACKETS = booleanPreferencesKey("auto_close_brackets")
        /**
         * Read only: the text-editing pad's grid from before panel layouts. Folded
         * into [customPanelLayouts] while no TEXT_EDIT layout is stored, and
         * cleared by the first write of one. See [foldLegacyPanelPrefs].
         */
        private val TEXT_EDIT_LAYOUT = stringPreferencesKey("text_edit_layout")
        /** The user's panel layouts (issue #63), a JSON list of [PanelLayoutSpec]. */
        private val PANEL_LAYOUTS = stringPreferencesKey("panel_layouts")
        private val RECAPITALIZE_SELECTION_WITH_SHIFT =
            booleanPreferencesKey("recapitalize_selection_with_shift")
        private val SUGGESTIONS = booleanPreferencesKey("suggestions")
        private val SHOW_SUGGESTIONS_ALL_FIELDS =
            booleanPreferencesKey("show_suggestions_all_fields")
        private val SUGGESTIONS_FIRST = booleanPreferencesKey("suggestions_first")
        private val SUGGESTION_PRIMARY_CENTER = booleanPreferencesKey("suggestion_primary_center")
        private val SUGGESTION_OVERFLOW = stringPreferencesKey("suggestion_overflow")
        private val BLOCK_OFFENSIVE_WORDS = booleanPreferencesKey("block_offensive_words")
        private val CONTEXT_RERANK = booleanPreferencesKey("context_rerank")
        private val LANGUAGE_DETECTION = booleanPreferencesKey("language_detection")
        private val LANGUAGE_DETECTION_BY_APP = booleanPreferencesKey("language_detection_by_app")
        /** Read only: the one switch for every language that [PHONETIC_ENGLISH_LANGS] replaced. */
        private val PHONETIC_AUTO_ENGLISH = booleanPreferencesKey("phonetic_auto_english")
        private val PHONETIC_ENGLISH_LANGS = stringSetPreferencesKey("phonetic_english_langs")
        private val PHONETIC_ENGLISH_SWITCH = booleanPreferencesKey("phonetic_english_switch")
        private val PHONETIC_FIXED_STRIP_LANGS = stringSetPreferencesKey("phonetic_fixed_strip_langs")

        /** `langId=SOURCE` entries, one per language that has picked one. */
        private val PHONETIC_STRIP_SOURCES = stringSetPreferencesKey("phonetic_strip_sources")

        /** What the old single switch meant while it was on: every language with a phonetic layout. */
        private val LEGACY_PHONETIC_ENGLISH_LANGS = setOf("bn", "hi")
        private val LANGUAGE_DETECTION_STRENGTH =
            stringPreferencesKey("language_detection_strength")
        private val NUMBER_ROW_CORRECTIONS = booleanPreferencesKey("number_row_corrections")
        private val NUMBER_PREDICTION = booleanPreferencesKey("number_prediction")
        private val AUTOCORRECT_SPLITS = booleanPreferencesKey("autocorrect_splits")
        private val REGISTER_PRIORS = booleanPreferencesKey("register_priors")
        private val TIMING_SIGNAL_STRENGTH = floatPreferencesKey("timing_signal_strength")
        private val CONTACT_SUGGESTIONS = booleanPreferencesKey("contact_suggestions")
        private val CONTACT_EMAIL_SUGGESTIONS =
            booleanPreferencesKey("contact_email_suggestions")
        private val CONTACT_EMAIL_SUGGESTIONS_IN_EMAIL_FIELDS =
            booleanPreferencesKey("contact_email_suggestions_in_email_fields")
        private val APP_NAME_SUGGESTIONS = booleanPreferencesKey("app_name_suggestions")
        private val SUGGESTION_BLACKLIST = stringSetPreferencesKey("suggestion_blacklist")
        private val SUGGESTION_BLACKLIST_SCOPE = stringPreferencesKey("suggestion_blacklist_scope")

        /**
         * One string set per language with its own blocked words (#136), the
         * language id after the prefix. Dynamic keys rather than one encoded
         * string: a set per key is what the backup already knows how to copy,
         * and a word with a separator in it can never corrupt another language.
         */
        private const val SUGGESTION_BLACKLIST_LANG_PREFIX = "suggestion_blacklist_lang_"

        private fun blacklistLanguageKey(languageId: String) =
            stringSetPreferencesKey(SUGGESTION_BLACKLIST_LANG_PREFIX + languageId)

        /** Every per-language blacklist in [p], keyed by language id. */
        private fun blacklistsByLanguage(p: Preferences): Map<String, Set<String>> {
            var out: MutableMap<String, Set<String>>? = null
            for ((key, value) in p.asMap()) {
                val name = key.name
                if (!name.startsWith(SUGGESTION_BLACKLIST_LANG_PREFIX)) continue
                val words = value as? Set<*> ?: continue
                if (words.isEmpty()) continue
                val map = out ?: LinkedHashMap<String, Set<String>>().also { out = it }
                map[name.removePrefix(SUGGESTION_BLACKLIST_LANG_PREFIX)] =
                    words.mapNotNullTo(LinkedHashSet()) { it as? String }
            }
            return out ?: emptyMap()
        }
        private val SPELLING_MAP_OFF_LANGS = stringSetPreferencesKey("spelling_map_off_langs")
        private val PHONETIC_SIBLINGS_OFF_LANGS = stringSetPreferencesKey("phonetic_siblings_off_langs")
        private val IMPORTED_ONLY_LANGS = stringSetPreferencesKey("imported_only_langs")
        private val WORD_PAIRS_OFF_LANGS = stringSetPreferencesKey("word_pairs_off_langs")
        private val WORD_MENU_ITEMS = stringSetPreferencesKey("word_menu_items")

        /**
         * Written into [WORD_MENU_ITEMS] beside the item names from the build
         * that added Synonyms (#321) on. A stored set without it was chosen
         * before Synonyms existed, so Synonyms is read as on rather than as
         * switched off by someone who never saw it.
         */
        private const val WORD_MENU_SYNONYMS_MARK = "~synonyms"
        private val SYNONYM_SOURCES = stringPreferencesKey("synonym_sources")
        private val WORD_RANK_CONTROL = stringPreferencesKey("word_rank_control")
        private val DELETE_EDITS_IMPORTED_LISTS = booleanPreferencesKey("delete_edits_imported_lists")
        private val LEARN_FROM_TEXT_SORT = stringPreferencesKey("learn_from_text_sort")
        private val LEARN_FROM_TEXT_PAIRS = booleanPreferencesKey("learn_from_text_pairs")
        private val INLINE_EMOJI_SEARCH = booleanPreferencesKey("inline_emoji_search")
        private val INLINE_AUTOFILL = booleanPreferencesKey("inline_autofill")
        private val GESTURE_TYPING = booleanPreferencesKey("gesture_typing")
        private val LETTER_SWIPE_ACTION = stringPreferencesKey("letter_swipe_action")
        private val GESTURE_SPACE_MULTI_WORD = booleanPreferencesKey("gesture_space_multi_word")
        private val GESTURE_SHIFT_CAPITALS = booleanPreferencesKey("gesture_shift_capitals")
        private val GESTURE_SHIFT_MODE = stringPreferencesKey("gesture_shift_glide_mode")
        private val GESTURE_AMBIGUITY_PICKER = booleanPreferencesKey("gesture_ambiguity_picker")
        private val GESTURE_PICKER_DWELL_MS = intPreferencesKey("gesture_picker_dwell_ms")
        private val GESTURE_PICKER_SENSITIVITY = stringPreferencesKey("gesture_picker_sensitivity")
        private val GESTURE_PICKER_HOLD_TO_ASK = booleanPreferencesKey("gesture_picker_hold_to_ask")
        private val GESTURE_PICKER_CHOICES = intPreferencesKey("gesture_picker_choices")
        private val GESTURE_APOSTROPHE_KEY = stringPreferencesKey("gesture_apostrophe_key")
        private val GESTURE_POSSESSIVE_KEY = stringPreferencesKey("gesture_possessive_key")
        /** The toggle the possessive swipe shipped as, read only to migrate it. */
        private val GESTURE_APOSTROPHE_S = booleanPreferencesKey("gesture_apostrophe_s")
        private val GESTURE_AUTO_SPACE = booleanPreferencesKey("gesture_auto_space")
        private val GESTURE_START_RADIUS = floatPreferencesKey("gesture_start_radius")
        private val GESTURE_END_RADIUS = floatPreferencesKey("gesture_end_radius")
        private val GESTURE_NEAR_RADIUS = floatPreferencesKey("gesture_near_radius")
        private val GESTURE_DWELL_FULL = floatPreferencesKey("gesture_dwell_full")
        private val GESTURE_LOOP_DOUBLE = booleanPreferencesKey("gesture_loop_double")
        private val GESTURE_LOOP_MIN_ARC = floatPreferencesKey("gesture_loop_min_arc")
        private val GESTURE_LOOP_EXTENT = floatPreferencesKey("gesture_loop_extent")
        private val GESTURE_LOOP_RADIUS = floatPreferencesKey("gesture_loop_radius")
        private val GESTURE_WIGGLE_DOUBLE = booleanPreferencesKey("gesture_wiggle_double")
        private val GESTURE_WIGGLE_EXTENT = floatPreferencesKey("gesture_wiggle_extent")
        private val GESTURE_WIGGLE_WEIGHT = floatPreferencesKey("gesture_wiggle_weight")
        private val GESTURE_START_THRESHOLD_SLOP = floatPreferencesKey("gesture_start_threshold_slop")
        private val GESTURE_POST_TYPE_COOLDOWN_MS = intPreferencesKey("gesture_post_type_cooldown_ms")
        private val GESTURE_HANDWRITE_DOT_COOLDOWN_MS = intPreferencesKey("gesture_handwrite_dot_cooldown_ms")
        private val GESTURE_TRAIL_WIDTH_DP = floatPreferencesKey("gesture_trail_width_dp")
        private val GESTURE_TRAIL_DURATION_MS = intPreferencesKey("gesture_trail_duration_ms")
        private val GESTURE_TRAIL_OPACITY = floatPreferencesKey("gesture_trail_opacity")
        private val GESTURE_WORD_PREVIEW = booleanPreferencesKey("gesture_word_preview")
        private val GESTURE_WORD_PREVIEW_OFFSET_Y = intPreferencesKey("gesture_word_preview_offset_y")
        private val GESTURE_WORD_PREVIEW_OFFSET_X = intPreferencesKey("gesture_word_preview_offset_x")
        private val GESTURE_WORD_PREVIEW_FONT_SP = intPreferencesKey("gesture_word_preview_font_sp")
        private val GESTURE_WORD_PREVIEW_BACKGROUND = longPreferencesKey("gesture_word_preview_background")
        private val GESTURE_WORD_PREVIEW_TEXT_COLOR = longPreferencesKey("gesture_word_preview_text_color")
        private val GESTURE_STRIP_PREVIEW_ONLY = booleanPreferencesKey("gesture_strip_preview_only")
        private val GESTURE_VOCABULARY = stringPreferencesKey("gesture_vocabulary")
        private val GESTURE_SANDBOX = stringPreferencesKey("gesture_sandbox")
        private val GESTURE_PREVIEW_STEADINESS = stringPreferencesKey("gesture_preview_steadiness")
        private val GESTURE_LOOK_AHEAD = stringPreferencesKey("gesture_look_ahead")
        private val GESTURE_COMMIT_COLOR = stringPreferencesKey("gesture_commit_color")
        private val GESTURE_COMMIT_COLOR_SCOPE =
            stringPreferencesKey("gesture_commit_color_scope")
        private val GESTURE_LEARN_SWIPE_STYLE = booleanPreferencesKey("gesture_learn_swipe_style")
        private val GESTURE_SHAPES_PER_WORD = intPreferencesKey("gesture_shapes_per_word")
        private val GESTURE_SEARCH_ALL_CHIP = booleanPreferencesKey("gesture_search_all_chip")
        private val GESTURE_SWIPE_STYLE_VERSION = intPreferencesKey("gesture_swipe_style_version")
        // Legacy boolean, read only to migrate into SPACE_LONG_SWIPE.
        private val SPACEBAR_CURSOR = booleanPreferencesKey("spacebar_cursor")
        private val SPACE_SHORT_SWIPE = stringPreferencesKey("space_short_swipe")
        private val SPACE_LONG_SWIPE = stringPreferencesKey("space_long_swipe")
        private val SPACEBAR_LANGUAGE_ARROWS = booleanPreferencesKey("spacebar_language_arrows")
        private val SPACEBAR_LABEL = stringPreferencesKey("spacebar_label")
        private val SYMBOLS_LONGPRESS_NUMPAD = booleanPreferencesKey("symbols_longpress_numpad")
        private val ENTER_LONGPRESS_EMOJI = booleanPreferencesKey("enter_longpress_emoji")
        private val SPACE_SWIPE_DOWN_HIDE = booleanPreferencesKey("space_swipe_down_hide")
        private val GLOBE_IN_ONE_PLACE = booleanPreferencesKey("globe_in_one_place")
        private val HINT_FLICK = booleanPreferencesKey("hint_flick")
        private val CAPITAL_FLICK = booleanPreferencesKey("capital_flick")
        private val GLOBE_TYPING_GUARD_MS = intPreferencesKey("globe_typing_guard_ms")
        private val GLOBE_DRAG_SHORTCUTS = booleanPreferencesKey("globe_drag_shortcuts")
        private val BOARD_CORNER_TOP = intPreferencesKey("board_corner_top")
        private val BOARD_CORNER_BOTTOM = intPreferencesKey("board_corner_bottom")
        private val BOARD_CORNERS = stringSetPreferencesKey("board_corners")
        private val SPACE_CURSOR_2D = booleanPreferencesKey("space_cursor_2d")
        private val HINT_FONT_SCALE = floatPreferencesKey("hint_font_scale")
        private val HINT_OFFSET = intPreferencesKey("hint_offset_dp")
        private val TRANSLITERATION_HINTS = stringPreferencesKey("transliteration_hints")
        private val FANCY_STYLE = stringPreferencesKey("fancy_style")
        private val FANCY_TOOL_STYLE = stringPreferencesKey("fancy_tool_style")
        private val FANCY_TOOL_KEEPS_LANGUAGE =
            booleanPreferencesKey("fancy_tool_keeps_language")
        private val FANCY_TOOL_AUTO_OFF = booleanPreferencesKey("fancy_tool_auto_off")
        private val CUSTOM_LAYOUT_TOOL = stringPreferencesKey("custom_layout_tool")
        private val NUMBER_ROW_SHIFT_SYMBOLS = booleanPreferencesKey("number_row_shift_symbols")
        private val NUMBER_ROW_IN_SYMBOLS = booleanPreferencesKey("number_row_in_symbols")
        private val BOTTOM_ROW_HEIGHT = intPreferencesKey("bottom_row_height")
        // Legacy, read-only (issue #41): the one symmetric pad. Still read so an
        // upgrade keeps the padding it had; never written again.
        private val SIDE_PAD_SCALE = floatPreferencesKey("side_pad_scale")
        private val SIDE_PAD_LEFT_SCALE = floatPreferencesKey("side_pad_left_scale")
        private val SIDE_PAD_RIGHT_SCALE = floatPreferencesKey("side_pad_right_scale")
        private val SPLIT_ONLY_LARGE = booleanPreferencesKey("split_only_large_screens")
        private val SHIFT_CAPS_LOCK_MS = intPreferencesKey("shift_caps_lock_ms")
        private val SHOW_ALL_POPUP_KEYS = booleanPreferencesKey("show_all_popup_keys")
        private val SHIFTED_POPUP_KEYS = booleanPreferencesKey("shifted_popup_keys")
        private val CURRENCY_KEYS = stringPreferencesKey("currency_keys")
        private val SPACE_HOLD_KEYS = stringPreferencesKey("space_hold_keys")
        private val SYMBOLS_RETURN_TO_LETTERS =
            booleanPreferencesKey("symbols_return_to_letters")
        private val SYMBOLS_RETURN_CHARS = stringPreferencesKey("symbols_return_chars")
        private val AUTO_SPACE_AFTER_SUGGESTION = booleanPreferencesKey("auto_space_after_suggestion")
        private val SKIP_TYPED_WORD = booleanPreferencesKey("skip_typed_word")
        private val EXPAND_USER_DICT_SHORTCUTS = booleanPreferencesKey("expand_user_dict_shortcuts")
        private val USE_SYSTEM_DICTIONARY = booleanPreferencesKey("use_system_dictionary")
        private val SNIPPET_MULTI_EXPAND = stringPreferencesKey("snippet_multi_expand")
        private val SYSTEM_SMART_REPLIES = booleanPreferencesKey("system_smart_replies")
        private val SMART_HIT_DETECTION = booleanPreferencesKey("smart_hit_detection")
        private val AUTOPILOT_STRENGTH = intPreferencesKey("autopilot_strength")
        private val AUTOPILOT_SHOW_EFFECT = booleanPreferencesKey("autopilot_show_effect")
        private val AUTOPILOT_OUTLINE = booleanPreferencesKey("autopilot_outline")
        private val OCTOPUS_ENABLED = booleanPreferencesKey("octopus_enabled")
        private val OCTOPUS_PLACEMENT = stringPreferencesKey("octopus_placement")
        private val OCTOPUS_DENSITY = intPreferencesKey("octopus_density")
        private val OCTOPUS_KINDS = stringPreferencesKey("octopus_kinds")
        private val OCTOPUS_DURING_GLIDE = stringPreferencesKey("octopus_during_glide")
        private val OCTOPUS_FLICK_COMMITS = booleanPreferencesKey("octopus_flick_commits")
        private val OCTOPUS_TAP_COMMITS = booleanPreferencesKey("octopus_tap_commits")
        private val OCTOPUS_FLICK_SENSITIVITY = stringPreferencesKey("octopus_flick_sensitivity")
        private val OCTOPUS_FONT_SCALE = floatPreferencesKey("octopus_font_scale")
        private val OCTOPUS_SUPPRESS_HINTS = booleanPreferencesKey("octopus_suppress_hints")
        private val OCTOPUS_LONG_PRESS_KEYS = booleanPreferencesKey("octopus_long_press_keys")
        private val OCTOPUS_WORDS_PER_KEY = intPreferencesKey("octopus_words_per_key")
        private val AUTOPILOT_VISUAL_SCALE = floatPreferencesKey("autopilot_visual_scale")
        private val SPACEBAR_DISPLAY = stringPreferencesKey("spacebar_display")
        private val LANGUAGE_PICKER_STYLE = stringPreferencesKey("language_picker_style")
        private val SPACE_HOLD_PICKER_FOR_LONG_RING = booleanPreferencesKey("space_hold_picker_for_long_ring")
        private val NUMERAL_SYSTEM_BY_LANG = stringPreferencesKey("numeral_system_by_lang")
        private val NUMERAL_COMMIT_SCOPE = stringPreferencesKey("numeral_commit_scope")
        private val SHIFT_ENTER_NEWLINE = booleanPreferencesKey("shift_enter_newline")
        private val PS_MANUAL = booleanPreferencesKey("power_saving_manual")
        private val PS_TRIGGER = stringPreferencesKey("power_saving_trigger")
        private val PS_BATTERY_PERCENT = intPreferencesKey("power_saving_battery_percent")
        private val PS_OFF_WHILE_CHARGING = booleanPreferencesKey("power_saving_off_while_charging")
        private val PS_DROP_HAPTICS = booleanPreferencesKey("power_saving_drop_haptics")
        private val PS_DROP_KEY_SOUND = booleanPreferencesKey("power_saving_drop_key_sound")
        private val PS_DROP_ANIMATIONS = booleanPreferencesKey("power_saving_drop_animations")
        private val PS_DROP_GLIDE_TRAIL = booleanPreferencesKey("power_saving_drop_glide_trail")
        private val PS_DROP_KEY_POPUP = booleanPreferencesKey("power_saving_drop_key_popup")
        private val PS_DROP_GESTURE_TYPING = booleanPreferencesKey("power_saving_drop_gesture_typing")
        private val PS_DROP_EMOJI_PREDICTION =
            booleanPreferencesKey("power_saving_drop_emoji_prediction")
        private val PS_DROP_SMART_CHIPS = booleanPreferencesKey("power_saving_drop_smart_chips")
        private val PS_DROP_BACKGROUND_NETWORK =
            booleanPreferencesKey("power_saving_drop_background_network")
        private val PS_DROP_SCREENSHOT_WATCH =
            booleanPreferencesKey("power_saving_drop_screenshot_watch")
        private val PS_DROP_ON_DEVICE_MODELS =
            booleanPreferencesKey("power_saving_drop_on_device_models")
        private val PS_DROP_TYPING_STATS =
            booleanPreferencesKey("power_saving_drop_typing_stats")
        private val PS_DROP_MEDIA_PIN =
            booleanPreferencesKey("power_saving_drop_media_pin")
        private val DS_MANUAL = booleanPreferencesKey("data_saver_manual")
        private val DS_TRIGGER = stringPreferencesKey("data_saver_trigger")
        private val DS_LINK_PREVIEWS = stringPreferencesKey("data_saver_link_previews")
        private val DS_DICTIONARY_LOOKUP = stringPreferencesKey("data_saver_dictionary_lookup")
        private val DS_PHOTO_BACKGROUNDS = stringPreferencesKey("data_saver_photo_backgrounds")
        private val DS_WEATHER_CHIP = stringPreferencesKey("data_saver_weather_chip")
        private val DS_VOCAB_AUDIO = stringPreferencesKey("data_saver_vocab_audio")
        private val DS_CURRENCY_RATES = stringPreferencesKey("data_saver_currency_rates")
        private val DS_ADDON_REFRESH = stringPreferencesKey("data_saver_addon_refresh")
        private val DS_MEDIA_SEARCH = stringPreferencesKey("data_saver_media_search")
        private val DS_WEB_SEARCH = stringPreferencesKey("data_saver_web_search")
        private val DS_ANIMATED_EMOJI = stringPreferencesKey("data_saver_animated_emoji")
        private val DS_DOWNLOADS = stringPreferencesKey("data_saver_downloads")
        private val DS_CLOUD_AI = stringPreferencesKey("data_saver_cloud_ai")
        private val DS_CLOUD_VOICE = stringPreferencesKey("data_saver_cloud_voice")
        private val BACKSPACE_SWIPE_DELETE = booleanPreferencesKey("backspace_swipe_delete")
        private val HARDWARE_KEYBOARD_INPUT = booleanPreferencesKey("hardware_keyboard_input")
        private val HW_SHORTCUTS_ENABLED = booleanPreferencesKey("hw_shortcuts_enabled")
        private val HW_PANEL_NAVIGATION = booleanPreferencesKey("hw_panel_navigation")
        private val HW_DPAD_KEY_NAVIGATION = booleanPreferencesKey("hw_dpad_key_navigation")
        private val HW_ESC_CLOSES_PANEL = booleanPreferencesKey("hw_esc_closes_panel")
        private val HW_SUGGESTION_HOTKEYS = stringPreferencesKey("hw_suggestion_hotkeys")
        private val HW_SUGGESTION_HINTS_ALWAYS =
            booleanPreferencesKey("hw_suggestion_hints_always")
        private val HW_TOOLBAR_DIGIT_CHORD = booleanPreferencesKey("hw_toolbar_digit_chord")
        private val HW_MAC_SHORTCUTS = booleanPreferencesKey("hw_mac_shortcuts")
        private val HW_LANGUAGE_SWITCH_CHORD = booleanPreferencesKey("hw_language_switch_chord")
        private val HW_HINT_MODIFIER_WORDS = booleanPreferencesKey("hw_hint_modifier_words")
        private val HW_AUTO_SHOW_UI = booleanPreferencesKey("hw_auto_show_ui")
        private val HW_LEADER = stringPreferencesKey("hw_leader")
        private val HW_PICKER_TIMEOUT_MS = intPreferencesKey("hw_picker_timeout_ms")
        private val HW_TOOL_LETTERS = stringPreferencesKey("hw_tool_letters")
        private val VOLUME_CURSOR = booleanPreferencesKey("volume_cursor")
        private val VOLUME_CURSOR_MEDIA_AWARE = booleanPreferencesKey("volume_cursor_media_aware")
        private val GLOBE_AS_EMOJI = booleanPreferencesKey("globe_as_emoji")
        private val SHOW_GLOBE_KEY = booleanPreferencesKey("show_globe_key")
        private val GLOBE_RECENT_ORDER = booleanPreferencesKey("globe_recent_order")
        private val OS_LANGUAGE_SWITCHER = booleanPreferencesKey("os_language_switcher")
        private val SUBTYPE_APP_NAME_FIRST = booleanPreferencesKey("subtype_app_name_first")
        private val PER_APP_LANGUAGE_ENABLED = booleanPreferencesKey("per_app_language_enabled")
        private val PER_APP_LAYOUT_MAP = stringPreferencesKey("per_app_layout_map")
        private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val ONBOARDING_PERSONA_LANGUAGES =
            stringPreferencesKey("onboarding_persona_languages")
        private val ONBOARDING_PERSONA_DEPTH = stringPreferencesKey("onboarding_persona_depth")
        private val ONBOARDING_PERSONA_PRIVACY = stringPreferencesKey("onboarding_persona_privacy")
        private val THEME_GALLERY_STYLE = stringPreferencesKey("theme_gallery_style")
        private val ADVANCED_OPEN = stringSetPreferencesKey("advanced_open")
        private val DEFAULT_WORDLIST_SIZE = stringPreferencesKey("default_wordlist_size")
        private val DICTIONARY_SORT = stringPreferencesKey("dictionary_sort")
        private val SETTINGS_ROW_ICONS = booleanPreferencesKey("settings_row_icons")
        private val SETTINGS_SCREEN_TRANSITIONS = booleanPreferencesKey("settings_screen_transitions")
        private val SYMBOL_ROW_HEIGHT = intPreferencesKey("symbol_row_height")
        private val SYMBOL_ROW_LINES = intPreferencesKey("symbol_row_lines")
        private val SYMBOL_ROW_SCROLL = stringPreferencesKey("symbol_row_scroll")
        private val WEATHER_REFRESH_MINUTES = intPreferencesKey("weather_refresh_minutes")
        private val WIKI_LINK_LIMIT = intPreferencesKey("wiki_link_limit")
        private val QR_MAX_CHARS = intPreferencesKey("qr_max_chars")
        private val PASSWORD_SYMBOLS = stringPreferencesKey("password_symbols")
        private val MANUAL_MODE_DURATION = stringPreferencesKey("manual_mode_duration")
        private val DICTIONARY_BAR_ENABLED = booleanPreferencesKey("dictionary_bar_enabled")
        private val DICTIONARY_BAR_FILTER = stringPreferencesKey("dictionary_bar_filter")
        private val CONJUNCT_BACKSPACE_LANGUAGES = stringPreferencesKey("conjunct_backspace_languages")

        /**
         * The old global switch, read once so an install that had it on keeps
         * cluster deletion in the languages it was actually deleting clusters
         * in. Never written again.
         */
        private val CONJUNCT_BACKSPACE = booleanPreferencesKey("conjunct_backspace")
        private val PINYIN_FUZZY = booleanPreferencesKey("pinyin_fuzzy")
        private val PINYIN_FUZZY_PAIRS = stringSetPreferencesKey("pinyin_fuzzy_pairs")
        private val PINYIN_DOUBLE_PINYIN = stringPreferencesKey("pinyin_double_pinyin")
        private val CJK_TRADITIONAL_OUTPUT = booleanPreferencesKey("cjk_traditional_output")
        private val JYUTPING_LAZY = booleanPreferencesKey("jyutping_lazy")
        private val KANA_LOOSE_MARKS = booleanPreferencesKey("kana_loose_marks")
        private val FULL_WIDTH_SPACE_LANGUAGES = stringSetPreferencesKey("full_width_space_languages")
        private val CJK_HAN_REGION = stringPreferencesKey("cjk_han_region")
        private val ONE_HANDED_MODE = stringPreferencesKey("one_handed_mode")
        // One-handed width leaves room for the rail on the inner edge, so it is
        // capped below 100%. Height scale never grows the keys, only shrinks.
        const val ONE_HANDED_WIDTH_MIN = 40
        const val ONE_HANDED_WIDTH_MAX = 85
        const val ONE_HANDED_HEIGHT_SCALE_MIN = 60
        const val ONE_HANDED_HEIGHT_SCALE_MAX = 100
        // Docked sizing limits, shared by the sliders and the inline resize
        // tool so a drag can never store what a slider could not.
        const val KEY_HEIGHT_MIN_DP = 32
        const val KEY_HEIGHT_MAX_DP = 100
        const val MAX_BOTTOM_PADDING_DP = 160
        // Per-orientation one-handed geometry. `portrait` = false suffix keeps
        // the two orientations in step by construction.
        private fun oneHandedWidthKey(landscape: Boolean) =
            intPreferencesKey("one_handed_width_${if (landscape) "landscape" else "portrait"}")
        private fun oneHandedHeightScaleKey(landscape: Boolean) =
            intPreferencesKey("one_handed_height_scale_${if (landscape) "landscape" else "portrait"}")
        private fun oneHandedSideKey(landscape: Boolean) =
            stringPreferencesKey("one_handed_side_${if (landscape) "landscape" else "portrait"}")
        private val LEARN_FROM_TYPING = booleanPreferencesKey("learn_from_typing")
        private val ADD_WORDS_TO_SYSTEM_DICTIONARY =
            booleanPreferencesKey("add_words_to_system_dictionary")
        private val CLIPBOARD_HISTORY = booleanPreferencesKey("clipboard_history")
        private val CLIPBOARD_EXPIRY_HOURS = intPreferencesKey("clipboard_expiry_hours")
        private val CLIPBOARD_MAX_ITEMS = intPreferencesKey("clipboard_max_items")
        private val CLIPBOARD_SENSITIVE_HANDLING =
            stringPreferencesKey("clipboard_sensitive_handling")
        private val CLIPBOARD_DETECT_SENSITIVE = booleanPreferencesKey("clipboard_detect_sensitive")
        private val CLIPBOARD_SENSITIVE_EXPIRY_MINUTES =
            intPreferencesKey("clipboard_sensitive_expiry_minutes")
        private val CLIPBOARD_LINK_PREVIEWS = booleanPreferencesKey("clipboard_link_previews")
        private val CLIPBOARD_TRACK_SOURCE = booleanPreferencesKey("clipboard_track_source")
        private val CLIPBOARD_SUGGEST_RECENT = booleanPreferencesKey("clipboard_suggest_recent")
        private val CLIPBOARD_COPIED_CODE_CHIP =
            stringPreferencesKey("clipboard_copied_code_chip")

        /**
         * The boolean [CLIPBOARD_COPIED_CODE_CHIP] replaced, read once to carry
         * an existing choice across. It only ever answered *whether* to offer a
         * copied code, so an explicit `true` becomes the widest option rather
         * than the narrow one it happened to mean at the time.
         */
        private val CLIPBOARD_SUGGEST_CODES_IN_CODE_FIELDS =
            booleanPreferencesKey("clipboard_suggest_codes_in_code_fields")
        private val PUNCTUATION_SUGGESTIONS = booleanPreferencesKey("punctuation_suggestions")
        /** Read only: the clipboard panel's bottom-row switch from before panel layouts; see [foldLegacyPanelPrefs]. */
        private val CLIPBOARD_BOTTOM_ROW = booleanPreferencesKey("clipboard_bottom_row")
        private val CLIPBOARD_PINNED_LAST = booleanPreferencesKey("clipboard_pinned_last")
        private val CLIPBOARD_SEARCH = booleanPreferencesKey("clipboard_search")
        private val CLIPBOARD_USER_SCREENSHOTS = booleanPreferencesKey("clipboard_user_screenshots")
        private val CLIPBOARD_CLEAR_AFTER_PASSWORD_PASTE =
            booleanPreferencesKey("clipboard_clear_after_password_paste")
        private val CLIPBOARD_DETECT_ENTITIES = booleanPreferencesKey("clipboard_detect_entities")
        private val CLIPBOARD_PHONE_FORMATS = stringSetPreferencesKey("clipboard_phone_formats")
        private val CLIPBOARD_FULL_BLEED = booleanPreferencesKey("clipboard_full_bleed")
        private val CLIPBOARD_VIEW = stringPreferencesKey("clipboard_view")
        private val CLIPBOARD_SHOW_NUMBERS = booleanPreferencesKey("clipboard_show_numbers")
        private val CLIPBOARD_UNDO_DELETE = booleanPreferencesKey("clipboard_undo_delete")
        private val CLIPBOARD_SWIPE_TO_DELETE = booleanPreferencesKey("clipboard_swipe_to_delete")
        private val CLIPBOARD_PREVIEW_LINES = intPreferencesKey("clipboard_preview_lines")
        private val CLIPBOARD_GRID_COLUMNS = intPreferencesKey("clipboard_grid_columns")
        private val CLIPBOARD_TIME_LABEL = stringPreferencesKey("clipboard_time_label")
        private val CLIPBOARD_MAX_TEXT_CHARS = intPreferencesKey("clipboard_max_text_chars")
        private val OTP_CHIP_ENABLED = booleanPreferencesKey("otp_chip_enabled")
        // Stored under its old name: the test behind it grew from "number
        // field" to "code box", but a user who turned it on meant the same
        // thing either way and must not be silently reset.
        private val OTP_CODE_FIELDS_ONLY = booleanPreferencesKey("otp_number_fields_only")
        private val OTP_EXPIRY_MINUTES = intPreferencesKey("otp_expiry_minutes")
        private val OTP_DISMISS_NOTIFICATION = booleanPreferencesKey("otp_dismiss_notification")
        private val OTP_PER_DIGIT_ENTRY = booleanPreferencesKey("otp_per_digit_entry")
        private val AUTO_BACKUP_ENABLED = booleanPreferencesKey(SettingsBackup.AUTO_BACKUP_ENABLED)
        private val AUTO_BACKUP_FOLDER_URI =
            stringPreferencesKey(SettingsBackup.AUTO_BACKUP_FOLDER_URI)
        private val AUTO_BACKUP_INTERVAL_HOURS = intPreferencesKey("auto_backup_interval_hours")
        private val AUTO_BACKUP_KEEP = intPreferencesKey("auto_backup_keep")
        private val AUTO_BACKUP_UNMETERED = booleanPreferencesKey("auto_backup_unmetered")
        private val AUTO_BACKUP_CHARGING = booleanPreferencesKey("auto_backup_charging")
        private val AUTO_BACKUP_SECTIONS = stringSetPreferencesKey("auto_backup_sections")
        private val AUTO_BACKUP_INCLUDE_SECRETS =
            booleanPreferencesKey("auto_backup_include_secrets")
        private val AUTO_BACKUP_ENCRYPT = booleanPreferencesKey("auto_backup_encrypt")
        private val AUTO_BACKUP_PASSPHRASE =
            stringPreferencesKey(SettingsBackup.AUTO_BACKUP_PASSPHRASE)
        private val AUTO_BACKUP_KDF_SALT = stringPreferencesKey(SettingsBackup.AUTO_BACKUP_KDF_SALT)
        private val AUTO_BACKUP_LAST_RUN_AT =
            longPreferencesKey(SettingsBackup.AUTO_BACKUP_LAST_RUN_AT)
        private val AUTO_BACKUP_LAST_ERROR =
            stringPreferencesKey(SettingsBackup.AUTO_BACKUP_LAST_ERROR)
        private val AUTO_BACKUP_DESTINATION = stringPreferencesKey("auto_backup_destination")
        private val AUTO_BACKUP_WEBDAV_URL = stringPreferencesKey("auto_backup_webdav_url")
        private val AUTO_BACKUP_WEBDAV_USER = stringPreferencesKey("auto_backup_webdav_user")
        private val AUTO_BACKUP_WEBDAV_PASSWORD =
            stringPreferencesKey(SettingsBackup.AUTO_BACKUP_WEBDAV_PASSWORD)
        private val AUTO_BACKUP_S3_ENDPOINT = stringPreferencesKey("auto_backup_s3_endpoint")
        private val AUTO_BACKUP_S3_REGION = stringPreferencesKey("auto_backup_s3_region")
        private val AUTO_BACKUP_S3_BUCKET = stringPreferencesKey("auto_backup_s3_bucket")
        private val AUTO_BACKUP_S3_PREFIX = stringPreferencesKey("auto_backup_s3_prefix")
        private val AUTO_BACKUP_S3_KEY_ID = stringPreferencesKey("auto_backup_s3_key_id")
        private val AUTO_BACKUP_S3_SECRET =
            stringPreferencesKey(SettingsBackup.AUTO_BACKUP_S3_SECRET)
        private val AUTO_BACKUP_S3_PATH_STYLE = booleanPreferencesKey("auto_backup_s3_path_style")
        private val AUTO_BACKUP_FTP_HOST = stringPreferencesKey("auto_backup_ftp_host")
        private val AUTO_BACKUP_FTP_PORT = intPreferencesKey("auto_backup_ftp_port")
        private val AUTO_BACKUP_FTP_USER = stringPreferencesKey("auto_backup_ftp_user")
        private val AUTO_BACKUP_FTP_PASSWORD =
            stringPreferencesKey(SettingsBackup.AUTO_BACKUP_FTP_PASSWORD)
        private val AUTO_BACKUP_FTP_PATH = stringPreferencesKey("auto_backup_ftp_path")
        private val AUTO_BACKUP_FTP_SECURE = booleanPreferencesKey("auto_backup_ftp_secure")
        private val AUTO_BACKUP_DROPBOX_TOKEN =
            stringPreferencesKey(SettingsBackup.AUTO_BACKUP_DROPBOX_TOKEN)
        private val AUTO_BACKUP_ONEDRIVE_TOKEN =
            stringPreferencesKey(SettingsBackup.AUTO_BACKUP_ONEDRIVE_TOKEN)
        private val AUTO_BACKUP_LOCATIONS = stringPreferencesKey(SettingsBackup.AUTO_BACKUP_LOCATIONS)
        private val AUTO_BACKUP_LOCATION_STATUS =
            stringPreferencesKey(SettingsBackup.AUTO_BACKUP_LOCATION_STATUS)
        private val EXPORT_SECTIONS = stringSetPreferencesKey("export_sections")
        private val AUTO_BACKUP_INCLUDE_KEYS = booleanPreferencesKey("auto_backup_include_keys")
        private val SYNC_ENABLED = booleanPreferencesKey(SettingsBackup.SYNC_ENABLED)
        private val SYNC_MODE = stringPreferencesKey(SettingsBackup.SYNC_MODE)
        private val SYNC_INTERVAL_HOURS = intPreferencesKey(SettingsBackup.SYNC_INTERVAL_HOURS)
        private val SYNC_LOCATION_ID = stringPreferencesKey(SettingsBackup.SYNC_LOCATION_ID)
        private val SYNC_LOCATION_IDS = stringSetPreferencesKey(SettingsBackup.SYNC_LOCATION_IDS)
        private val SYNC_SECTIONS = stringSetPreferencesKey(SettingsBackup.SYNC_SECTIONS)
        private val SYNC_INCLUDE_SECRETS = booleanPreferencesKey(SettingsBackup.SYNC_INCLUDE_SECRETS)
        private val SYNC_KEEP_LOCAL = stringSetPreferencesKey(SettingsBackup.SYNC_KEEP_LOCAL)
        private val SYNC_LAST_RUN_AT = longPreferencesKey(SettingsBackup.SYNC_LAST_RUN_AT)
        private val SYNC_LAST_ERROR = stringPreferencesKey(SettingsBackup.SYNC_LAST_ERROR)
        private val LONG_PRESS_DELAY = intPreferencesKey("long_press_delay")
        // The pre-split single interval. Still read, as the fallback for both
        // keys below, so a cadence tuned before the split survives the upgrade.
        private val KEY_REPEAT_INTERVAL = intPreferencesKey("key_repeat_interval")
        private val KEY_REPEAT_DELETE = intPreferencesKey("key_repeat_delete")
        private val KEY_REPEAT_WORD_DELETE = intPreferencesKey("key_repeat_word_delete")
        private val KEY_REPEAT_SPACE = intPreferencesKey("key_repeat_space")
        private val KEY_REPEAT_CUSTOM = intPreferencesKey("key_repeat_custom")
        private val KEY_REPEAT_START_DELAY = intPreferencesKey("key_repeat_start_delay")
        private val LONG_PRESS_HINTS = booleanPreferencesKey("long_press_hints")
        private val LONG_PRESS_A_SELECT_ALL = booleanPreferencesKey("long_press_a_select_all")
        private val LONG_PRESS_C_COPY = booleanPreferencesKey("long_press_c_copy")
        private val LONG_PRESS_V_PASTE = booleanPreferencesKey("long_press_v_paste")
        private val LONG_PRESS_X_CUT = booleanPreferencesKey("long_press_x_cut")
        private val LONG_PRESS_Z_UNDO = booleanPreferencesKey("long_press_z_undo")
        private val LONG_PRESS_Y_REDO = booleanPreferencesKey("long_press_y_redo")
        private val LONG_PRESS_LETTERS = stringPreferencesKey("long_press_letters")
        private val LONG_PRESS_ACTION_FIRST =
            booleanPreferencesKey("long_press_action_first")
        private val EMOJI_TOOLBAR = booleanPreferencesKey("emoji_toolbar")
        private val COLORED_TOOL_ICONS = booleanPreferencesKey("colored_tool_icons")
        private val TOOL_COLOR_OVERRIDES = stringPreferencesKey("tool_color_overrides")
        private val TOOL_ICON_GRADIENTS = booleanPreferencesKey("tool_icon_gradients")
        private val TOOL_COLOR_END_OVERRIDES = stringPreferencesKey("tool_color_end_overrides")
        private val ICON_PACK_ID = stringPreferencesKey("icon_pack_id")
        private val ICON_OVERRIDES = stringPreferencesKey("icon_overrides")
        private val INCOGNITO = booleanPreferencesKey("incognito")
        private val TOOLBAR_TOOLS = stringPreferencesKey("toolbar_tools")
        private val TOOLBAR_GREEDY = booleanPreferencesKey("toolbar_greedy")
        private val TOOLBAR_ENABLED = booleanPreferencesKey("toolbar_enabled")
        private val TOOLBAR_SWIPE_DOWN_HIDE = booleanPreferencesKey("toolbar_swipe_down_hide")
        private val TOOLBAR_ONLY_HW_KEYBOARD = booleanPreferencesKey("toolbar_only_hw_keyboard")
        private val REVERSE_TOOLBAR_RTL = booleanPreferencesKey("reverse_toolbar_rtl")
        private val TOOLBAR_HEIGHT = intPreferencesKey("toolbar_height")
        private val TOOLBAR_SCROLLABLE = booleanPreferencesKey("toolbar_scrollable")
        private val TOOLBAR_HIDE_WHEN_LOCKED = booleanPreferencesKey("toolbar_hide_when_locked")
        private val TOOLBAR_LABELS = booleanPreferencesKey("toolbar_labels")
        private val TOOLBAR_LABEL_SIZE = intPreferencesKey("toolbar_label_size")
        private val TOOL_CIRCLE_RADIUS = intPreferencesKey("tool_circle_radius")
        private val TOOL_SHAPE = stringPreferencesKey("tool_circle_shape")
        private val TOOLBAR_TOOL_WIDTH = intPreferencesKey("toolbar_tool_width")
        private val TOOLBAR_PADDING_TOP = intPreferencesKey("toolbar_padding_top")
        private val TOOLBAR_PADDING_BOTTOM = intPreferencesKey("toolbar_padding_bottom")
        private val TOOLBAR_PLACEMENT = stringPreferencesKey("toolbar_placement")
        private val TOOLBAR_SHOW_STRIP = booleanPreferencesKey("toolbar_show_strip")
        private val TOOLBAR_HOLD_ACTIONS = stringPreferencesKey("toolbar_hold_actions")
        private val TOOLBAR_DRAG_REARRANGE = booleanPreferencesKey("toolbar_drag_rearrange")
        private val THEMES_PANEL_BUILTINS = stringSetPreferencesKey("themes_panel_builtins")
        private val COMMA_AS_EMOJI = booleanPreferencesKey("comma_as_emoji")
        private val SWAP_COMMA_GLOBE = booleanPreferencesKey("swap_comma_globe")
        private val EMOJI_TAB_MODE = stringPreferencesKey("emoji_tab_mode")
        private val EMOJI_CLEAR_RECENTS_BUTTON = booleanPreferencesKey("emoji_clear_recents_button")
        private val EMOJI_LONG_PRESS_NAME = booleanPreferencesKey("emoji_long_press_name")
        private val EMOJI_PREDICTION = booleanPreferencesKey("emoji_prediction")
        private val EMOJI_BAR_MODE = stringPreferencesKey("emoji_bar_mode")
        private val EMOJI_BAR_CONTENT = stringPreferencesKey("emoji_bar_content")
        private val EMOJI_INSERT_MODE = stringPreferencesKey("emoji_insert_mode")
        private val EMOJI_DEFAULT_SKIN_TONE = stringPreferencesKey("emoji_default_skin_tone")
        private val EMOJI_TONE_OVERRIDE_LAST_USED =
            booleanPreferencesKey("emoji_tone_override_last_used")
        private val EMOJI_CLOSE_AFTER_INSERT = booleanPreferencesKey("emoji_close_after_insert")
        private val EMOJI_HIDE_UNRENDERABLE = booleanPreferencesKey("emoji_hide_unrenderable")
        private val EMOJI_BAR_SCROLLABLE = booleanPreferencesKey("emoji_bar_scrollable")
        private val EMOJI_BAR_COUNT = intPreferencesKey("emoji_bar_count")
        private val EMOJI_GRID_CELL_SIZE = intPreferencesKey("emoji_grid_cell_size")
        private val EMOJI_GRID_EMOJI_SIZE = intPreferencesKey("emoji_grid_emoji_size")
        private val EMOJI_KAOMOJI_TABS = booleanPreferencesKey("emoji_kaomoji_tabs")
        private val EMOJI_KEYWORD_PACK_VERSION = intPreferencesKey("emoji_keyword_pack_version")
        private val EMOJI_DISABLED_KEYWORD_LANGS = stringSetPreferencesKey("emoji_disabled_keyword_langs")
        private val EMOJI_USAGE_VERSION = intPreferencesKey("emoji_usage_version")
        private val EMOJI_RECENTS_LIMIT = intPreferencesKey("emoji_recents_limit")
        private val MEDIA_GRID_COLUMNS = intPreferencesKey("media_grid_columns")
        private val EMOJI_ANIMATED = booleanPreferencesKey("emoji_animated")
        private val EMOJI_SEND_AS_STICKER = booleanPreferencesKey("emoji_send_as_sticker")
        private val EMOJI_CATEGORY_ORDER = stringPreferencesKey("emoji_category_order")
        private val EMOJI_HIDDEN_CATEGORIES = stringSetPreferencesKey("emoji_hidden_categories")
        // JSON rather than the comma-joined form its neighbours use: the values
        // are emoji sequences, and a ZWJ sequence is a string whose parts must
        // stay glued. JSON is the encoding already trusted with layout specs.
        private val EMOJI_CATEGORY_EMOJI_ORDER =
            stringPreferencesKey("emoji_category_emoji_order")
        private val EMOJI_AUTO_DOWNLOAD_KEYWORDS =
            booleanPreferencesKey("emoji_auto_download_keywords")
        // Stored as the DISABLED set so tools added in future versions
        // default to enabled even for users who already toggled some off.
        private val DISABLED_TOOLS = stringPreferencesKey("disabled_tools")
        private val TOOLBOX_ORDER = stringPreferencesKey("toolbox_order")
        private val TOOLBOX_HINT_DISMISSED = booleanPreferencesKey("toolbox_hint_dismissed")
        private val FLASHLIGHT_AUTO_OFF = booleanPreferencesKey("flashlight_auto_off")
        private val COMPASS_SHOW_DEGREES = booleanPreferencesKey("compass_show_degrees")
        private val COMPASS_SHOW_QIBLA = booleanPreferencesKey("compass_show_qibla")
        private val KEY_SOUND_STYLE = stringPreferencesKey("key_sound_style")
        private val KEY_SOUND_VOLUME = floatPreferencesKey("key_sound_volume")
        private val KEY_SOUND_CUSTOM_ID = stringPreferencesKey("key_sound_custom_id")
        private val KEY_SOUND_PACK_ID = stringPreferencesKey("key_sound_pack_id")
        private val KEY_SOUND_RELEASE = booleanPreferencesKey("key_sound_release")
        private val LEVEL_SHOW_ANGLES = booleanPreferencesKey("level_show_angles")
        private val REDO_USES_CTRL_Y = booleanPreferencesKey("redo_uses_ctrl_y")
        private val MOON_SOUTHERN = booleanPreferencesKey("moon_southern_hemisphere")
        private val NETWORK_LOG_KEEP = booleanPreferencesKey("network_log_keep")
        private val NETWORK_LOG_ON_KEYBOARD = booleanPreferencesKey("network_log_on_keyboard")
        private val WEATHER_FAHRENHEIT = booleanPreferencesKey("weather_fahrenheit")
        private val WEATHER_LAT = floatPreferencesKey("weather_lat")
        private val WEATHER_LON = floatPreferencesKey("weather_lon")
        private val WEATHER_PLACE = stringPreferencesKey("weather_place")
        private val WEATHER_AUTO_FETCH = booleanPreferencesKey("weather_auto_fetch")
        // Superseded by CALENDAR_ALT_ONE/TWO, still read once to carry the old
        // Bengali/Hijri switches over to the new pair of picks.
        private val CALENDAR_SHOW_BENGALI = booleanPreferencesKey("calendar_show_bengali")
        private val CALENDAR_SHOW_HIJRI = booleanPreferencesKey("calendar_show_hijri")
        private val CALENDAR_ALT_ONE = stringPreferencesKey("calendar_alt_one")
        private val CALENDAR_ALT_TWO = stringPreferencesKey("calendar_alt_two")
        private val CALENDAR_WEEKEND = stringPreferencesKey("calendar_weekend")
        private val HIJRI_ADJUST_DAYS = intPreferencesKey("hijri_adjust_days")
        private val HANDWRITING_STYLUS_ONLY = booleanPreferencesKey("handwriting_stylus_only")
        private val HANDWRITING_COMMIT_DELAY = intPreferencesKey("handwriting_commit_delay")
        private val HANDWRITING_AUTO_SPACE = booleanPreferencesKey("handwriting_auto_space")
        // Legacy boolean the three-way voice_ui_mode replaced; still read as
        // the fallback so an existing strip-mode choice survives the update.
        private val VOICE_STRIP_MODE = booleanPreferencesKey("voice_strip_mode")
        private val VOICE_UI_MODE = stringPreferencesKey("voice_ui_mode")
        private val VOICE_TYPING_MODE = stringPreferencesKey("voice_typing_mode")
        private val VOICE_BAR_ACTIVE = booleanPreferencesKey("voice_bar_active")
        private val VOICE_BAR_VERTICAL = booleanPreferencesKey("voice_bar_vertical")
        private val VOICE_BAR_SNAP = intPreferencesKey("voice_bar_snap")
        private val VOICE_BAR_EDGE_RIGHT = booleanPreferencesKey("voice_bar_edge_right")
        private val VOICE_BAR_Y_BIAS = floatPreferencesKey("voice_bar_y_bias")
        private val VOICE_BAR_DOCK_BIAS = floatPreferencesKey("voice_bar_dock_bias")
        private val VOICE_HOLD_TO_TALK_MS = intPreferencesKey("voice_hold_to_talk_ms")
        private val VOICE_HOLD_PICKS_MODE = booleanPreferencesKey("voice_hold_picks_mode")
        private val VOICE_UI_RETURN_MODE = stringPreferencesKey("voice_ui_return_mode")
        private val VOICE_BAR_INLINE = booleanPreferencesKey("voice_bar_inline")
        private val VOICE_CONTINUOUS = booleanPreferencesKey("voice_continuous")
        private val VOICE_SPOKEN_PUNCTUATION = booleanPreferencesKey("voice_spoken_punctuation")
        private val VOICE_ENGINE = stringPreferencesKey("voice_engine")
        private val WHISPER_MODEL_ID = stringPreferencesKey("whisper_model_id")
        private val WHISPER_MODEL_BY_LANG = stringPreferencesKey("whisper_model_by_lang")
        private val WHISPER_TRANSLATE = booleanPreferencesKey("whisper_translate")
        private val VOICE_SERVER_URL = stringPreferencesKey("voice_server_url")
        private val VOICE_SERVER_KEY = stringPreferencesKey("voice_server_key")
        private val VOICE_SERVER_MODEL = stringPreferencesKey("voice_server_model")
        private val VOICE_SERVER_SEND_LANGUAGE = booleanPreferencesKey("voice_server_send_language")
        private val VOICE_BIAS_PERSONAL_WORDS = booleanPreferencesKey("voice_bias_personal_words")
        private val VOICE_BIAS_WORDS = stringPreferencesKey("voice_bias_words")
        private val VOICE_SERVER_PROMPT = stringPreferencesKey("voice_server_prompt")
        private val CAMERA_PREFER_FRONT = booleanPreferencesKey("camera_prefer_front")
        private val CAMERA_TIMER_SECONDS = intPreferencesKey("camera_timer_seconds")
        private val CAMERA_CAPTURE_MAX_PX = intPreferencesKey("camera_capture_max_px")
        private val CLIPBOARD_PASTE_CHIP_SECONDS = intPreferencesKey("clipboard_paste_chip_seconds")
        private val LAUNCHER_MAX_RECENTS = intPreferencesKey("launcher_max_recents")
        private val CAMERA_MIRROR_FRONT = booleanPreferencesKey("camera_mirror_front")
        private val CAMERA_SHUTTER_SOUND = booleanPreferencesKey("camera_shutter_sound")
        private val CAMERA_HAPTICS = booleanPreferencesKey("camera_haptics")
        private val CAMERA_SAVE_TO_GALLERY = booleanPreferencesKey("camera_save_to_gallery")
        private val CAMERA_FULL_FRAME = booleanPreferencesKey("camera_full_frame")
        private val CAMERA_SEARCH_BUTTON = booleanPreferencesKey("camera_search_button")
        private val CAMERA_SEARCH_WITH = stringPreferencesKey("camera_search_with")
        private val CAMERA_SEARCH_ENGINE = stringPreferencesKey("camera_search_engine")
        private val CAMERA_SEARCH_CUSTOM_URL = stringPreferencesKey("camera_search_custom_url")
        private val CAMERA_SEARCH_CUSTOM_FIELD = stringPreferencesKey("camera_search_custom_field")
        private val DOC_SCAN_SAVE_TO_GALLERY = booleanPreferencesKey("doc_scan_save_to_gallery")
        private val QR_SAVE_TO_GALLERY = booleanPreferencesKey("qr_save_to_gallery")
        private val STICKER_SEND_MODE = stringPreferencesKey("sticker_send_mode")
        private val GIF_SEND_MODE = stringPreferencesKey("gif_send_mode")
        private val QR_SEND_MODE = stringPreferencesKey("qr_send_mode")
        private val DICTIONARY_AUTO_LOOKUP = booleanPreferencesKey("dictionary_auto_lookup")
        private val DICTIONARY_SOURCES = stringPreferencesKey("dictionary_sources")
        private val TEXT_EDIT_REPEAT_MS = intPreferencesKey("text_edit_repeat_ms")
        private val CURSOR_TOOLS_REPEAT_ON_HOLD =
            booleanPreferencesKey("cursor_tools_repeat_on_hold")
        private val TOOLBOX_REPEAT_TOOLS = stringPreferencesKey("toolbox_repeat_tools")
        private val SELECTION_MODE_HOLD = booleanPreferencesKey("selection_mode_hold")
        private val SELECTION_MODE_MULTI_TAP = booleanPreferencesKey("selection_mode_multi_tap")
        private val TRACKPAD_STEP_X_DP = intPreferencesKey("trackpad_step_x_dp")
        private val TRACKPAD_STEP_Y_DP = intPreferencesKey("trackpad_step_y_dp")
        private val TRACKPAD_HOLD_TO_OPEN = booleanPreferencesKey("trackpad_hold_to_open")
        private val TRACKPAD_MULTI_TAP = booleanPreferencesKey("trackpad_multi_tap")
        private val TRACKPAD_HAPTICS = booleanPreferencesKey("trackpad_haptics")
        private val TRACKPAD_TRAIL = booleanPreferencesKey("trackpad_trail")
        private val TRACKPAD_MAGNIFIER = booleanPreferencesKey("trackpad_magnifier")
        private val VOCAB_NUDGES = booleanPreferencesKey("vocab_nudges")
        private val VOCAB_NUDGE_SELF = booleanPreferencesKey("vocab_nudge_self")
        private val VOCAB_NUDGE_SCOPE = stringPreferencesKey("vocab_nudge_scope")
        private val VOCAB_NUDGE_LEVEL = stringPreferencesKey("vocab_nudge_level")
        private val VOCAB_COOLDOWN = stringPreferencesKey("vocab_cooldown")
        private val VOCAB_CHIP_TAP = stringPreferencesKey("vocab_chip_tap")
        private val VOCAB_RELATED_TAP = stringPreferencesKey("vocab_related_tap")
        private val VOCAB_SCHEDULER = stringPreferencesKey("vocab_scheduler")
        private val VOCAB_DAILY_GOAL = intPreferencesKey("vocab_daily_goal")
        private val VOCAB_WOTD_CARD = booleanPreferencesKey("vocab_wotd_card")
        private val VOCAB_WOTD_CHIP = booleanPreferencesKey("vocab_wotd_chip")
        private val VOCAB_WORD_INTERVAL = stringPreferencesKey("vocab_word_interval")
        private val VOCAB_CHIP_TIMES = intPreferencesKey("vocab_chip_times")
        private val VOCAB_AUDIO_SOURCE = stringPreferencesKey("vocab_audio_source")
        private val VOCAB_ACCENT = stringPreferencesKey("vocab_accent")
        private val VOCAB_TTS_RATE = floatPreferencesKey("vocab_tts_rate")
        private val VOCAB_TTS_PITCH = floatPreferencesKey("vocab_tts_pitch")
        private val VOCAB_CARD_FIELDS = stringPreferencesKey("vocab_card_fields")
        private val VOCAB_TRANSLATION_LANGS = stringPreferencesKey("vocab_translation_langs")
        private val DOUBLE_SPACE_WINDOW_MS = intPreferencesKey("double_space_window_ms")
        private val SPACE_CURSOR_STEP_DP = intPreferencesKey("space_cursor_step_dp")
        private val SPACE_CURSOR_MAGNIFIER = booleanPreferencesKey("space_cursor_magnifier")
        private val BACKSPACE_WORD_STEP_DP = intPreferencesKey("backspace_word_step_dp")
        private val BACKSPACE_SWIPE_UNIT = stringPreferencesKey("backspace_swipe_unit")
        private val BACKSPACE_SWIPE_PREVIEW = booleanPreferencesKey("backspace_swipe_preview")
        private val BACKSPACE_CHAR_STEP_DP = intPreferencesKey("backspace_char_step_dp")
        private val DELETE_HOLD_DELETES_WORDS = booleanPreferencesKey("delete_hold_deletes_words")
        private val FORWARD_DELETE_SWIPE = booleanPreferencesKey("forward_delete_swipe")
        private val PUNCTUATION_CHIPS = stringPreferencesKey("punctuation_chips")
        private val SUGGESTION_SLOT_COUNT = intPreferencesKey("suggestion_slot_count")
        private val SUGGESTION_SCROLLABLE = booleanPreferencesKey("suggestion_scrollable")
        private val SUGGESTION_PRIMARY_COLOR = longPreferencesKey("suggestion_primary_color")
        private val SUGGESTION_CHIP_PADDING = intPreferencesKey("suggestion_chip_padding")
        private val NUMPAD_CALCULATOR_LAYOUT = booleanPreferencesKey("numpad_calculator_layout")

        /**
         * The old key, back when the numpad defaulted to calculator order and the
         * toggle opted into phone order. The default flipped, so the toggle flipped
         * with it — an old `true` means the same grid as a new `false`.
         */
        private val NUMPAD_PHONE_LAYOUT = booleanPreferencesKey("numpad_phone_layout")
        private val INCOGNITO_PAUSES_CLIPBOARD = booleanPreferencesKey("incognito_pauses_clipboard")
        private val INCOGNITO_PAUSES_LEARNING = booleanPreferencesKey("incognito_pauses_learning")
        private val AUTO_INCOGNITO = booleanPreferencesKey("auto_incognito")
        private val OCR_AUTO_SELECT_WORDS = booleanPreferencesKey("ocr_auto_select_words")
        private val OCR_ENGINE = stringPreferencesKey("ocr_engine")
        private val QR_SCAN_HAPTICS = booleanPreferencesKey("qr_scan_haptics")
        private val QR_SCAN_AUTO_INSERT = booleanPreferencesKey("qr_scan_auto_insert")
        private val QR_SCAN_LINK_PREVIEWS = booleanPreferencesKey("qr_scan_link_previews")
        private val CURRENCY_DECIMALS = intPreferencesKey("currency_decimals")
        private val CURRENCY_LABEL = stringPreferencesKey("currency_label")
        private val CURRENCY_CACHE_HOURS = intPreferencesKey("currency_cache_hours")
        private val FIAT_PROVIDERS = stringPreferencesKey("fiat_rate_providers")
        private val CRYPTO_ENABLED = booleanPreferencesKey("crypto_enabled")
        private val CRYPTO_PROVIDERS = stringPreferencesKey("crypto_rate_providers")
        private val CRYPTO_CACHE_MINUTES = intPreferencesKey("crypto_cache_minutes")
        private val CRYPTO_TICKERS = stringSetPreferencesKey("crypto_tickers")
        private val CURRENCY_AUTO_FETCH = booleanPreferencesKey("currency_auto_fetch")

        // The settings app's fingerprint lock; see [AppLockSettings]. Flat
        // keys like everything else here, even though the in-memory shape is
        // its own object rather than a KeyboardSettings field.
        private val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        private val APP_LOCK_TARGETS = stringSetPreferencesKey("app_lock_targets")
        private val APP_LOCK_RELOCK = stringPreferencesKey("app_lock_relock")
        private val APP_LOCK_ALLOW_CREDENTIAL = booleanPreferencesKey("app_lock_allow_credential")

        // Automation intents; see [AutomationSettings]. One flag per
        // [AutomationPermission], keyed by its stable [AutomationPermission.key].
        private val AUTOMATION_ENABLED = booleanPreferencesKey("automation_enabled")
        private fun automationKey(permission: AutomationPermission) =
            booleanPreferencesKey("automation_allow_${permission.key}")
        private val CRYPTO_DECIMALS = intPreferencesKey("crypto_decimals")
        private val GRAMMAR_DEBOUNCE_MS = intPreferencesKey("grammar_debounce_ms")
        private val UNIT_CONVERT_LAST = stringPreferencesKey("unit_convert_last")
        private val COMPOUND_UNITS = booleanPreferencesKey("compound_units")
        private val TOOLBOX_COLUMNS = intPreferencesKey("toolbox_columns")
        private val TOOLBOX_LAYOUT = stringPreferencesKey("toolbox_layout")
        private val TOOLBOX_PILL_COLUMNS = intPreferencesKey("toolbox_pill_columns")
        private val TOOLBOX_PILL_FILLED = booleanPreferencesKey("toolbox_pill_filled")
        private val TOOLBOX_PAGINATE = booleanPreferencesKey("toolbox_paginate")
        private val TOOLBOX_PAGE_SIZE = intPreferencesKey("toolbox_page_size")
        private val TOOLBOX_LABEL_SIZE = intPreferencesKey("toolbox_label_size")
        private val TOOLBOX_HIDDEN_TOOLS = stringPreferencesKey("toolbox_hidden_tools")
        private val SUGGESTION_TEXT_SCALE = floatPreferencesKey("suggestion_text_scale")
        private val LEARNED_WORD_MIN_COUNT = intPreferencesKey("learned_word_min_count")
        private val NEW_WORD_SIGHTINGS = intPreferencesKey("new_word_sightings")
        private val ASK_BEFORE_LEARNING = booleanPreferencesKey("ask_before_learning")
        private val OFFER_NEAR_MISS_CORRECTIONS =
            booleanPreferencesKey("offer_near_miss_corrections")
        private val UNDO_CORRECTION_CHIP = booleanPreferencesKey("undo_correction_chip")
        private val UNDO_CHIP_OBVIOUSNESS = floatPreferencesKey("undo_chip_obviousness")
        private val LEARN_FROM_CORRECTIONS = booleanPreferencesKey("learn_from_corrections")
        private val ADAPT_TO_TAPS = booleanPreferencesKey("adapt_to_taps")
        private val CORRECTIONS_VERSION = intPreferencesKey("corrections_version")
        private val EMOJI_ROW_ABOVE_TOOLBAR = booleanPreferencesKey("emoji_row_above_toolbar")
        private val TRANSLATE_TARGET_LANG = stringPreferencesKey("translate_target_lang")
        private val TRANSLATE_ENGINE = stringPreferencesKey("translate_engine")
        private val TRANSLATE_DOWNLOADED_FIRST = booleanPreferencesKey("translate_downloaded_first")
        private val TRANSLATE_ONLY_DOWNLOADED = booleanPreferencesKey("translate_only_downloaded")
        private val DEEPL_API_KEY = stringPreferencesKey("deepl_api_key")
        private val DEEPL_ENDPOINT = stringPreferencesKey("deepl_endpoint")
        private val DEEPL_TRANSLATE = booleanPreferencesKey("deepl_translate")
        private val DEEPL_WRITE = booleanPreferencesKey("deepl_write")
        private val DEEPL_WRITE_STYLE = stringPreferencesKey("deepl_write_style")
        private val GRAMMAR_DIALECT = stringPreferencesKey("grammar_dialect")
        private val GRAMMAR_HIDDEN_KINDS = stringSetPreferencesKey("grammar_hidden_kinds")
        private val SPELL_CHECKER_NO_SUGGESTIONS =
            booleanPreferencesKey("spell_checker_no_suggestions")
        private val TRANSLATE_API_KEY = stringPreferencesKey("translate_api_key")
        private val KLIPY_API_KEY = stringPreferencesKey("klipy_api_key")
        private val BRAVE_API_KEY = stringPreferencesKey("brave_api_key")
        private val GIPHY_API_KEY = stringPreferencesKey("giphy_api_key")
        private val GIF_SOURCE_MODE = stringPreferencesKey("gif_source_mode")
        private val GIF_CONTENT_FILTER = stringPreferencesKey("gif_content_filter")
        private val GIF_RESULT_LIMIT = intPreferencesKey("gif_result_limit")
        private val STICKER_SUGGEST = booleanPreferencesKey("sticker_suggest")
        private val STICKER_SUGGEST_STYLE = stringPreferencesKey("sticker_suggest_style")
        private val STICKER_SUGGEST_TRIGGER = stringPreferencesKey("sticker_suggest_trigger")
        private val SEARCH_SAFE = booleanPreferencesKey("search_safe")
        private val SEARCH_RESULT_COUNT = intPreferencesKey("search_result_count")
        private val WIKI_LANGUAGE = stringPreferencesKey("wiki_language")
        private val WIKI_LINKS_MARKDOWN = booleanPreferencesKey("wiki_links_markdown")
        // Tab-separated (symbols are single graphemes; some are commas).
        private val SYMBOL_RECENTS = stringPreferencesKey("symbol_recents")
        private val SYMBOL_ROW_ENABLED = booleanPreferencesKey("symbol_row_enabled")
        // Tab-separated ids (custom set names are user text; ids are safe).
        private val SYMBOL_ROW_SETS = stringPreferencesKey("symbol_row_sets")
        private val SYMBOL_ROW_ACTIVE_SET = stringPreferencesKey("symbol_row_active_set")
        private val CUSTOM_SYMBOL_SETS = stringPreferencesKey("custom_symbol_sets")
        private val BAR_ORDER = stringPreferencesKey("bar_order")
        private val EMOJI_FULL_BLEED = booleanPreferencesKey("emoji_full_bleed")
        private val MEDIA_FULL_BLEED = booleanPreferencesKey("media_full_bleed")
        private val MODE_TOOL_ORDER_EDITS = booleanPreferencesKey("mode_tool_order_edits")
        private val MODE_TOOL_ORDER_HINT = booleanPreferencesKey("mode_tool_order_hint")
        private val MODES_ENABLED = booleanPreferencesKey("modes_enabled")
        private val KEYBOARD_MODES = stringPreferencesKey("keyboard_modes")
        private val MODE_SEED_VERSION = intPreferencesKey("mode_seed_version")
        private val LAUNCHER_SORT = stringPreferencesKey("launcher_sort")
        private val LAUNCHER_SHOW_LABELS = booleanPreferencesKey("launcher_show_labels")
        private val LAUNCHER_RECENTS_ENABLED = booleanPreferencesKey("launcher_recents_enabled")
        private val LAUNCHER_DRILLDOWN = booleanPreferencesKey("launcher_drilldown")
        private val LAUNCHER_SHOW_NON_EXPORTED =
            booleanPreferencesKey("launcher_show_non_exported")
        // Tab-separated package names (package names never contain tabs).
        private val LAUNCHER_PINNED = stringPreferencesKey("launcher_pinned")
        private val LAUNCHER_RECENTS = stringPreferencesKey("launcher_recents")
        private val LAUNCHER_GRID_COLUMNS = intPreferencesKey("launcher_grid_columns")
        private val LAUNCHER_ICON_SIZE = intPreferencesKey("launcher_icon_size")
        private val LAUNCHER_ICON_SHAPE = stringPreferencesKey("launcher_icon_shape")
        private val LAUNCHER_HIDDEN = stringPreferencesKey("launcher_hidden")
        private val LAUNCHER_OPEN_MODE = stringPreferencesKey("launcher_open_mode")
        private val LAUNCHER_COMBOS = stringPreferencesKey("launcher_combos")
        private val MEDIA_PIN_WHILE_PLAYING = booleanPreferencesKey("media_pin_while_playing")
        private val SELF_HOSTED_LIBRETRANSLATE_URL = stringPreferencesKey("self_hosted_libretranslate_url")
        private val SELF_HOSTED_LIBRETRANSLATE_KEY = stringPreferencesKey("self_hosted_libretranslate_key")
        private val SELF_HOSTED_SEARX_URL = stringPreferencesKey("self_hosted_searx_url")
        private val SELF_HOSTED_COMMONS_URL = stringPreferencesKey("self_hosted_commons_url")
        private val SELF_HOSTED_ENDPOINTS = stringPreferencesKey("self_hosted_endpoints")
        private val SELF_HOSTED_REPOS = stringPreferencesKey("self_hosted_repos")
        // Absent means "never chosen", which takes the seeded defaults; an
        // empty set is a real choice (nothing counts as music) and is kept.
        private val MEDIA_MUSIC_APPS = stringSetPreferencesKey("media_music_apps")
        private val KDE_ENABLED = booleanPreferencesKey("kde_enabled")
        private val KDE_DEVICE_NAME = stringPreferencesKey("kde_device_name")
        private val KDE_LIFETIME = stringPreferencesKey("kde_lifetime")
        private val KDE_AUTO_CONNECT = booleanPreferencesKey("kde_auto_connect")
        private val KDE_CLIPBOARD_RECEIVE = booleanPreferencesKey("kde_clipboard_receive")
        private val KDE_CLIPBOARD_SEND = booleanPreferencesKey("kde_clipboard_send")
        private val KDE_REMOTE_TYPING = booleanPreferencesKey("kde_remote_typing")
        private val KDE_REMOTE_TYPING_PIPELINE = booleanPreferencesKey("kde_remote_typing_pipeline")
        private val KDE_PAD_SENSITIVITY = floatPreferencesKey("kde_pad_sensitivity")
        private val KDE_PAD_ACCELERATION = booleanPreferencesKey("kde_pad_acceleration")
        private val KDE_SCROLL_SPEED = floatPreferencesKey("kde_scroll_speed")
        private val KDE_NATURAL_SCROLL = booleanPreferencesKey("kde_natural_scroll")
        private val KDE_TAP_TO_CLICK = booleanPreferencesKey("kde_tap_to_click")
        private val KDE_PAD_HAPTICS = booleanPreferencesKey("kde_pad_haptics")
        private val KDE_BATTERY_REPORT = booleanPreferencesKey("kde_battery_report")
        private val KDE_EXPOSE_MEDIA = booleanPreferencesKey("kde_expose_media")
        private val KDE_SHARE_SHEET = booleanPreferencesKey("kde_share_sheet")
        private val KDE_RECEIVE_FILES = booleanPreferencesKey("kde_receive_files")
        private val KDE_COMPOSE_MODE = booleanPreferencesKey("kde_compose_mode")
        private val KDE_LAST_TAB = stringPreferencesKey("kde_last_tab")
        private val KDE_HOSTS = stringSetPreferencesKey("kde_hosts")
        private val SMART_SUGGESTIONS = booleanPreferencesKey("smart_suggestions")
        private val SMART_CALC = booleanPreferencesKey("smart_calc")
        private val SMART_CURRENCY = booleanPreferencesKey("smart_currency")
        private val SMART_UNITS = booleanPreferencesKey("smart_units")
        private val SMART_TOOL_KEYWORDS = booleanPreferencesKey("smart_tool_keywords")
        private val SMART_CHIP_DATES = booleanPreferencesKey("smart_chip_dates")
        private val SMART_CHIP_WEATHER = booleanPreferencesKey("smart_chip_weather")
        private val SMART_CHIP_LOOKUPS = booleanPreferencesKey("smart_chip_lookups")
        private val SMART_CHIP_INTENTS = booleanPreferencesKey("smart_chip_intents")
        private val SMART_CHIP_GIFS = booleanPreferencesKey("smart_chip_gifs")
        private val SMART_CHIP_NUMBERS = booleanPreferencesKey("smart_chip_numbers")
        private val SMART_CHIP_NUMBER_GROUPING = stringPreferencesKey("smart_chip_number_grouping")

        private val SELECTION_MACROS_ENABLED = booleanPreferencesKey("selection_macros_enabled")
        private val SELECTION_MACROS_PLACEMENT = stringPreferencesKey("selection_macros_placement")
        private val SELECTION_MACROS_DETECT = booleanPreferencesKey("selection_macros_detect")

        /**
         * The macros that are on, by [SelectionMacro] name.
         *
         * A set and not a flag apiece because the list is expected to grow, and
         * because "which of these are on" is one preference to the person
         * setting it. Read together with [SELECTION_MACROS_LIST_VERSION]: an
         * unset key, or one stored under an older list version, means the
         * shipped set, so a build whose defaults changed hands every user the
         * new list once (see `SelectionMacroCodec`).
         */
        private val SELECTION_MACROS_ON = stringSetPreferencesKey("selection_macros_on")
        private val SELECTION_MACROS_LIST_VERSION = intPreferencesKey("selection_macros_list_version")
        /** The row's order, tab-joined names; same version rule as the on-list. */
        private val SELECTION_MACROS_ORDER = stringPreferencesKey("selection_macros_order")
        private val SELECTION_MACROS_AI_ACTIONS = stringPreferencesKey("selection_macros_ai_actions")
        private val SELECTION_MACROS_TIME_ZONES = stringPreferencesKey("selection_macros_time_zones")
        private val TOOL_KEYWORDS = stringPreferencesKey("tool_keywords")
        private val TOOL_KEYWORD_CASE = stringPreferencesKey("tool_keyword_case")
        private val CALC_DEGREES = booleanPreferencesKey("calc_degrees")
        private val CALC_PHONE_LAYOUT = booleanPreferencesKey("calc_phone_layout")
        private val CALC_PRECISION = intPreferencesKey("calc_precision")
        private val CURRENCY_FROM = stringPreferencesKey("currency_from")
        private val CURRENCY_TO = stringPreferencesKey("currency_to")
        private val PW_LENGTH = intPreferencesKey("pw_length")
        private val PW_UPPERCASE = booleanPreferencesKey("pw_uppercase")
        private val PW_DIGITS = booleanPreferencesKey("pw_digits")
        private val PW_SYMBOLS = booleanPreferencesKey("pw_symbols")
        private val PW_EXCLUDE_AMBIGUOUS = booleanPreferencesKey("pw_exclude_ambiguous")
        private val PW_PASSPHRASE_MODE = booleanPreferencesKey("pw_passphrase_mode")
        private val PP_WORD_COUNT = intPreferencesKey("pp_word_count")
        private val PP_SEPARATOR = stringPreferencesKey("pp_separator")
        private val PP_CAPITALIZE = booleanPreferencesKey("pp_capitalize")
        private val PP_INCLUDE_DIGIT = booleanPreferencesKey("pp_include_digit")
        private val TT_MODE = stringPreferencesKey("tt_mode")
        private val TT_DURATION = intPreferencesKey("tt_duration")
        private val TT_WORD_COUNT = intPreferencesKey("tt_word_count")
        private val TT_PUNCTUATION = booleanPreferencesKey("tt_punctuation")
        private val TT_NUMBERS = booleanPreferencesKey("tt_numbers")
        private val TT_GLIDE = booleanPreferencesKey("tt_glide")
        private val TT_SUGGESTIONS = booleanPreferencesKey("tt_suggestions")
        private val TT_BESTS = stringPreferencesKey("tt_bests")
        private val TT_HISTORY = stringPreferencesKey("tt_history")
        private val TT_COMPLETED = intPreferencesKey("tt_completed")
        private val TT_ACHIEVEMENTS = stringPreferencesKey("tt_achievements")
        private val TYPING_STATS_ENABLED = booleanPreferencesKey("typing_stats_enabled")
        private val STATS_VERSION = intPreferencesKey("stats_version")
        private val QR_SIZE_PX = intPreferencesKey("qr_size_px")
        private val QR_ECC = stringPreferencesKey("qr_ecc")
        private val AI_PROVIDER = stringPreferencesKey("ai_provider")
        private val AI_ANTHROPIC_KEY = stringPreferencesKey("ai_anthropic_key")
        private val AI_OPENAI_KEY = stringPreferencesKey("ai_openai_key")
        private val AI_GEMINI_KEY = stringPreferencesKey("ai_gemini_key")
        private val AI_ANTHROPIC_MODEL = stringPreferencesKey("ai_anthropic_model")
        private val AI_OPENAI_MODEL = stringPreferencesKey("ai_openai_model")
        private val AI_GEMINI_MODEL = stringPreferencesKey("ai_gemini_model")
        private val AI_OLLAMA_URL = stringPreferencesKey("ai_ollama_url")
        private val AI_OLLAMA_MODEL = stringPreferencesKey("ai_ollama_model")
        private val AI_LM_STUDIO_URL = stringPreferencesKey("ai_lm_studio_url")
        private val AI_LM_STUDIO_MODEL = stringPreferencesKey("ai_lm_studio_model")
        private val AI_XAI_KEY = stringPreferencesKey("ai_xai_key")
        private val AI_XAI_MODEL = stringPreferencesKey("ai_xai_model")
        private val AI_DEEPSEEK_KEY = stringPreferencesKey("ai_deepseek_key")
        private val AI_DEEPSEEK_MODEL = stringPreferencesKey("ai_deepseek_model")
        private val AI_COMPATIBLE_URL = stringPreferencesKey("ai_compatible_url")
        private val AI_COMPATIBLE_KEY = stringPreferencesKey("ai_compatible_key")
        private val AI_COMPATIBLE_MODEL = stringPreferencesKey("ai_compatible_model")
        private val AI_MAX_TOKENS = intPreferencesKey("ai_max_tokens")
        private val AI_LOCAL_CONTEXT_TOKENS = intPreferencesKey("ai_local_context_tokens")
        private val AI_HISTORY_ENABLED = booleanPreferencesKey("ai_history_enabled")
        private val AI_HISTORY_MAX = intPreferencesKey("ai_history_max")
        private val AI_KEEP_CHATS = booleanPreferencesKey("ai_keep_chats")
        private val AI_CHAT_ENTER_SENDS = booleanPreferencesKey("ai_chat_enter_sends")
        private val AI_PANEL_CHAT = booleanPreferencesKey("ai_panel_chat")
        private val AI_DOWNLOAD_UNMETERED = booleanPreferencesKey("ai_download_unmetered_only")
        private val AI_BEFORE_CURSOR_CHARS = intPreferencesKey("ai_before_cursor_chars")
        private val AI_DIFF_VIEW = booleanPreferencesKey("ai_diff_view")
        private val AI_DIFF_OPENS_FIRST = booleanPreferencesKey("ai_diff_opens_first")
        private val AI_CUSTOM_ACTIONS = stringPreferencesKey("ai_custom_actions")
        private val AI_ACTION_ORDER = stringPreferencesKey("ai_action_order")
        private val AI_ACTIONS_OFF = stringPreferencesKey("ai_actions_off")
        private val AI_TRANSLATE_TO = stringPreferencesKey("ai_translate_to")
        // Where a per-action prompt override used to live, one key each. Read
        // only now, and folded into the action list on every read; see
        // [legacyAiPrompts]. Not deleted, because a settings backup taken
        // before the change still carries them.
        private val AI_PROMPT_REWRITE = stringPreferencesKey("ai_prompt_rewrite")
        private val AI_PROMPT_SUMMARIZE = stringPreferencesKey("ai_prompt_summarize")
        private val AI_PROMPT_TRANSLATE = stringPreferencesKey("ai_prompt_translate")
        private val AI_PROMPT_IMPROVE = stringPreferencesKey("ai_prompt_improve")
        private val AI_PROMPT_FIX_GRAMMAR = stringPreferencesKey("ai_prompt_fix_grammar")
        private val AI_PROMPT_EXPLAIN = stringPreferencesKey("ai_prompt_explain")
        private val AI_PROMPT_CONTINUE = stringPreferencesKey("ai_prompt_continue")

        /** The old per-action prompt keys, by the action id each belongs to. */
        private fun legacyAiPrompts(p: Preferences): Map<String, String> = buildMap {
            fun take(id: String, key: Preferences.Key<String>) {
                p[key]?.takeIf { it.isNotBlank() }?.let { put(id, it) }
            }
            take(BuiltInAiActions.REWRITE_ID, AI_PROMPT_REWRITE)
            take(BuiltInAiActions.SUMMARIZE_ID, AI_PROMPT_SUMMARIZE)
            take(BuiltInAiActions.TRANSLATE_ID, AI_PROMPT_TRANSLATE)
            take(BuiltInAiActions.IMPROVE_ID, AI_PROMPT_IMPROVE)
            take(BuiltInAiActions.FIX_GRAMMAR_ID, AI_PROMPT_FIX_GRAMMAR)
            take(BuiltInAiActions.EXPLAIN_ID, AI_PROMPT_EXPLAIN)
            take(BuiltInAiActions.CONTINUE_ID, AI_PROMPT_CONTINUE)
        }
        private val AI_LOCAL_MODEL_ID = stringPreferencesKey("ai_local_model_id")
        private val AI_LOCAL_BACKEND = stringPreferencesKey("ai_local_backend")
        private val HF_TOKEN = stringPreferencesKey("hf_token")
        private val AI_SHOW_THINKING = booleanPreferencesKey("ai_show_thinking")
        private val AI_PANEL_MODEL_PICKER = booleanPreferencesKey("ai_panel_model_picker")
    }

    /**
     * The live settings.
     *
     * Unlocked, this is the DataStore, and each emission republishes the
     * device-protected mirror so the next direct boot draws the keyboard the
     * user actually configured. Locked, it is the mirror itself — the DataStore
     * is not merely empty then but unreadable, so it is never touched.
     *
     * The switch is a [flatMapLatest] on [unlocked]: an unlock while the
     * keyboard is on screen tears down the mirror flow and re-collects the real
     * one, and existing collectors just see one more emission.
     */
    /**
     * The default of every setting, as one object [mapPreferences] reads each
     * field's fallback out of.
     *
     * Built once. It used to be constructed per emission, which meant building
     * the whole 250-field object — and the forty nested settings objects, and
     * the registry lookups behind the language defaults — to answer questions
     * about the handful of keys the store happened to be missing. It is an
     * immutable data class whose defaults are constants and registry entries,
     * so one instance answers for every emission.
     */
    private val storedDefaults get() = SettingsDefaults

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val settings: Flow<KeyboardSettings> = unlocked
        .flatMapLatest { isUnlocked ->
            // The duplicate filter sits INSIDE each branch, deliberately.
            // DataStore re-emits after every successful write whether or not
            // the write changed anything, and several of the things this app
            // stores get rewritten with the value they already held; each of
            // those cost a full [mapPreferences] — 250 fields, two JSON
            // decodes — and, because the renderer compares the settings object
            // by instance, a theme and key-grid rebuild downstream.
            //
            // Filtering *outside* the flatMapLatest would have been a bug: an
            // unlock switches branches, and if the real store happened to hold
            // preferences equal to the mirror's last snapshot the switch would
            // emit nothing. The keyboard applies [restrictedToDirectBoot] to
            // whatever this publishes, so a swallowed emission there leaves it
            // in its locked-session shape — no custom fonts, no contacts, half
            // the tools missing — until some unrelated setting is next written.
            // Per branch, a switch always delivers the new flow's first value.
            if (isUnlocked) {
                context.dataStore.data
                    .onEach { locked.write(it) }
                    // The mirror write is disk work; keep it off whichever
                    // dispatcher the collector (the IME's main-thread scope)
                    // happens to be on.
                    .flowOn(Dispatchers.IO)
                    .distinctUntilChanged()
            } else {
                locked.snapshots().distinctUntilChanged()
            }
        }
        // Run the mapping off the main thread. The IME collects on
        // Dispatchers.Main.immediate, so without this the layout and theme
        // JSON was decoded on the thread drawing the keyboard, on the very
        // frame it was trying to appear. [mapPreferences] touches no Context,
        // so it is free to move; its one piece of outside state is the
        // shipped-layout catalogue, whose JSON half parses a layout the first
        // time it is resolved — one more reason this belongs off main. The
        // keyboard re-resolves the layout for itself on every field focus
        // rather than relying on this object's copy, so a mapping that ran
        // before the layout index was read is not what decides which grid
        // gets drawn.
        .map { mapPreferences(it) }
        // Every reader of settings also brings the service addresses up to date,
        // so a download manager deep in a feature module reads the address the
        // user set without being handed the settings. See [ServiceEndpoints].
        .onEach {
            ServiceEndpoints.update(it.selfHosted.endpoints, it.selfHosted.repos)
            // Same reasoning for the network log's switch: whoever reads
            // settings keeps the log's idea of "on" current.
            NetLog.enabled = it.networkLog.keep
        }
        .flowOn(Dispatchers.Default)

    /**
     * Called when the platform broadcasts that credential-encrypted storage has
     * become readable ([android.content.Intent.ACTION_USER_UNLOCKED]). Flips
     * every read and write back to the real store and re-emits [settings] from
     * it, discarding whatever the locked session wrote to the mirror.
     */
    fun onUserUnlocked() {
        unlocked.value = true
    }

    /**
     * Every write goes through here so that exactly one place knows which store
     * is writable. Locked, edits land in the device-protected mirror: the
     * keyboard's own toggles keep working on the lock screen, and the first
     * emission after unlock overwrites them.
     *
     * The edit runs on [Dispatchers.IO], not on the caller's dispatcher.
     * DataStore takes its write lock first and then runs [transform] in the
     * caller's context, so an edit launched from the keyboard's main-thread
     * scope held the lock until the main looper got round to the transform.
     * A looper that never does — a Robolectric test that ends without idling
     * it — left the process-wide store locked, and every later write in that
     * JVM waited forever. Off the main thread is also where the JSON these
     * transforms decode and re-encode belongs.
     */
    private suspend fun editPrefs(transform: suspend (MutablePreferences) -> Unit) {
        withContext(Dispatchers.IO) {
            if (unlocked.value) context.dataStore.edit { transform(it) }
            else locked.edit { transform(it) }
        }
    }

    /**
     * Deletes every stored setting, both the real store and the direct-boot
     * mirror, so the app comes back on its defaults.
     *
     * Goes through the store rather than deleting the file it is kept in: the
     * keyboard service holds the same DataStore open in the same process, and a
     * file that vanishes under it leaves the running keyboard on stale values
     * that the next write puts straight back. Clearing the keys instead emits
     * an empty preference set, which every collector already handles — it is
     * what a first run looks like.
     */
    suspend fun clearAllPreferences() {
        editPrefs { it.clear() }
        locked.clear()
    }

    /**
     * The possessive swipe before it had a key of its own (#169): a toggle, on
     * by default, that borrowed the glide's apostrophe key. Read only while
     * the new key is unset. A user who had the swipe working keeps it on the
     * same key — an unwritten toggle was an on one — and everyone else starts
     * from the default, which is off.
     */
    private fun legacyPossessiveKey(p: Preferences, defaults: KeyboardSettings): GlideApostropheKey {
        if (p[GESTURE_APOSTROPHE_S] == false) return defaults.gesture.possessiveKey
        val borrowed = p[GESTURE_APOSTROPHE_KEY]
            ?.let { runCatching { GlideApostropheKey.valueOf(it) }.getOrNull() }
            ?: return defaults.gesture.possessiveKey
        return if (borrowed.sourceChar != null) borrowed else defaults.gesture.possessiveKey
    }

    private fun mapPreferences(p: Preferences): KeyboardSettings {
        val defaults = storedDefaults
        // Layouts resolve first: the input mode is read off the active layout,
        // so it has to be known before the settings object is built. The
        // pre-registry migration lives in resolveLayoutSelection.
        val customLayouts = p[CUSTOM_LAYOUTS]?.let { LayoutCodec.decodeList(it) }
            ?: defaults.customLayouts
        val layoutSelection = resolveLayoutSelection(
            storedLayoutId = p[ACTIVE_LAYOUT_ID],
            storedInputMode = p[INPUT_MODE],
            storedEnabledLayoutIds = p[ENABLED_LAYOUT_IDS],
            storedEnabledModes = p[ENABLED_MODES],
            customLayouts = customLayouts,
            defaultActiveId = defaults.activeLayoutId,
            defaultEnabledIds = defaults.enabledLayoutIds,
        )
        return KeyboardSettings(
            activeLayoutId = layoutSelection.active.id,
            enabledLayoutIds = layoutSelection.enabledLayoutIds,
            recentLayoutIds = p[RECENT_LAYOUT_IDS]?.split(',')?.filter { it.isNotEmpty() }
                ?: defaults.recentLayoutIds,
            customLayouts = customLayouts,
            enabledLanguages = layoutSelection.enabledLanguages,
            secondaryLanguages = p[SECONDARY_LANGUAGES]?.let { decodeSecondaryLanguages(it) }
                ?: defaults.secondaryLanguages,
            language = layoutSelection.active.language(),
            script = layoutSelection.active.script(),
            themeMode = p[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: defaults.themeMode,
            dynamicColor = p[DYNAMIC_COLOR] ?: defaults.dynamicColor,
            keyboardThemeId = p[KEYBOARD_THEME_ID] ?: defaults.keyboardThemeId,
            customThemes = p[CUSTOM_THEMES]?.let { ThemeCodec.decodeList(it) }
                ?: defaults.customThemes,
            autoTheme = readAutoTheme(p, defaults),
            photoBackground = readPhotoBackground(p, defaults),
            keyHeightDp = p[KEY_HEIGHT] ?: defaults.keyHeightDp,
            numberRowHeightDp = p[NUMBER_ROW_HEIGHT] ?: p[KEY_HEIGHT] ?: defaults.numberRowHeightDp,
            bottomPaddingDp = p[BOTTOM_PADDING],
            splitKeyboard = p[SPLIT_KEYBOARD] ?: defaults.splitKeyboard,
            splitGapPercent = p[SPLIT_GAP_PERCENT] ?: defaults.splitGapPercent,
            floatingKeyboard = p[FLOATING_KEYBOARD] ?: defaults.floatingKeyboard,
            floatingWidthDp = p[FLOATING_WIDTH] ?: defaults.floatingWidthDp,
            floatingHeightScale = p[FLOATING_HEIGHT_SCALE] ?: defaults.floatingHeightScale,
            floatingXFraction = p[FLOATING_X] ?: defaults.floatingXFraction,
            floatingYFraction = p[FLOATING_Y] ?: defaults.floatingYFraction,
            persistentKeyboard = p[PERSISTENT_KEYBOARD] ?: defaults.persistentKeyboard,
            keyboardWidthPercent = p[KEYBOARD_WIDTH_PERCENT] ?: defaults.keyboardWidthPercent,
            keyboardAlignment = p[KEYBOARD_ALIGNMENT]
                ?.let { runCatching { KeyboardAlignment.valueOf(it) }.getOrNull() }
                ?: defaults.keyboardAlignment,
            keyGapScale = p[KEY_GAP_SCALE] ?: defaults.keyGapScale,
            keyCornerRadiusDp = p[KEY_CORNER_RADIUS] ?: defaults.keyCornerRadiusDp,
            fontScale = p[FONT_SCALE] ?: defaults.fontScale,
            sizingOverrides = readSizingOverrides(p, defaults),
            keyFontId = p[KEY_FONT_ID] ?: defaults.keyFontId,
            customFontName = p[CUSTOM_FONT_NAME] ?: defaults.customFontName,
            scriptFontIds = scriptFontIdsFromPrefs(p, defaults),
            customScriptFontNames = customScriptFontNamesFromPrefs(p, defaults),
            lexiconVersion = p[LEXICON_VERSION] ?: defaults.lexiconVersion,
            customDictVersion = p[CUSTOM_DICT_VERSION] ?: defaults.customDictVersion,
            autoDownloadLanguageData = p[AUTO_DOWNLOAD_LANGUAGE_DATA]
                ?: defaults.autoDownloadLanguageData,
            autoPairRomanized = p[AUTO_PAIR_ROMANIZED] ?: defaults.autoPairRomanized,
            morseCommitMs = p[MORSE_COMMIT_MS]?.coerceIn(MorseCommitMsRange)
                ?: defaults.morseCommitMs,
            emojiFont = p[EMOJI_FONT]
                ?.let { runCatching { EmojiFontChoice.valueOf(it) }.getOrNull() }
                ?: defaults.emojiFont,
            emojiFontInstalled = EmojiFontSettings(
                installedId = p[EMOJI_FONT_INSTALLED_ID] ?: defaults.emojiFontInstalled.installedId,
            ),
            haptics = readHaptics(p, defaults),
            feedback = readFeedback(p, defaults),
            sound = readSound(p, defaults),
            popup = popupFromPrefs(p, defaults),
            accessibility = readAccessibility(p, defaults),
            reduceMotion = p[REDUCE_MOTION] ?: defaults.reduceMotion,
            numberRow = p[NUMBER_ROW] ?: defaults.numberRow,
            correction = readCorrection(p, defaults),
            autoText = readAutoText(p, defaults),
            suggestions = p[SUGGESTIONS] ?: defaults.suggestions,
            suggestionSources = readSuggestionSources(p, defaults),
            gestureTyping = p[GESTURE_TYPING] ?: defaults.gestureTyping,
            letterSwipeAction = p[LETTER_SWIPE_ACTION]
                ?.let { runCatching { LetterSwipeAction.valueOf(it) }.getOrNull() }
                ?: defaults.letterSwipeAction,
            gesture = readGesture(p, defaults),
            spaceShortSwipe = p[SPACE_SHORT_SWIPE]
                ?.let { runCatching { SpaceSwipeAction.valueOf(it) }.getOrNull() }
                ?: defaults.spaceShortSwipe,
            // Users who had explicitly turned spacebar cursor control off
            // keep it off until they pick a new swipe action.
            spaceLongSwipe = p[SPACE_LONG_SWIPE]
                ?.let { runCatching { SpaceSwipeAction.valueOf(it) }.getOrNull() }
                ?: if (p[SPACEBAR_CURSOR] == false) SpaceSwipeAction.NONE else defaults.spaceLongSwipe,
            spacebarLanguageArrows = p[SPACEBAR_LANGUAGE_ARROWS]
                ?: defaults.spacebarLanguageArrows,
            spacebarLabel = p[SPACEBAR_LABEL] ?: defaults.spacebarLabel,
            backspaceSwipeDelete = p[BACKSPACE_SWIPE_DELETE] ?: defaults.backspaceSwipeDelete,
            hardwareKeyboardInput = p[HARDWARE_KEYBOARD_INPUT] ?: defaults.hardwareKeyboardInput,
            hardwareKeyboard = readHardwareKeyboard(p, defaults),
            volumeCursor = p[VOLUME_CURSOR] ?: defaults.volumeCursor,
            volumeCursorMediaAware = p[VOLUME_CURSOR_MEDIA_AWARE] ?: defaults.volumeCursorMediaAware,
            globeAsEmoji = p[GLOBE_AS_EMOJI] ?: defaults.globeAsEmoji,
            showGlobeKey = p[SHOW_GLOBE_KEY] ?: defaults.showGlobeKey,
            globeRecentOrder = p[GLOBE_RECENT_ORDER] ?: defaults.globeRecentOrder,
            osLanguageSwitcher = p[OS_LANGUAGE_SWITCHER] ?: defaults.osLanguageSwitcher,
            subtypeAppNameFirst = p[SUBTYPE_APP_NAME_FIRST] ?: defaults.subtypeAppNameFirst,
            perAppLanguage = readPerAppLanguage(p, defaults),
            onboardingDone = p[ONBOARDING_DONE] ?: defaults.onboardingDone,
            onboarding = readOnboarding(p, defaults),
            appUi = readAppUi(p, defaults),
            toolLimits = readToolLimits(p, defaults),
            rows = readRows(p, defaults),
            conjunctBackspaceLanguages = conjunctLanguagesFromPrefs(p, layoutSelection.enabledLanguages),
            cjk = readCjk(p, defaults),
            oneHandedMode = p[ONE_HANDED_MODE]
                ?.let { runCatching { OneHandedMode.valueOf(it) }.getOrNull() }
                ?: defaults.oneHandedMode,
            oneHanded = readOneHanded(p, defaults),
            learnFromTyping = p[LEARN_FROM_TYPING] ?: defaults.learnFromTyping,
            addWordsToSystemDictionary =
                p[ADD_WORDS_TO_SYSTEM_DICTIONARY] ?: defaults.addWordsToSystemDictionary,
            clipboard = readClipboard(p, defaults),
            otp = readOtp(p, defaults),
            autoBackup = readAutoBackup(p, defaults),
            suggestionStrip = readSuggestionStrip(p, defaults),
            longPressDelayMs = p[LONG_PRESS_DELAY] ?: defaults.longPressDelayMs,
            keyRepeat = readKeyRepeat(p, defaults),
            longPressHints = p[LONG_PRESS_HINTS] ?: defaults.longPressHints,
            octopus = readOctopus(p, defaults),
            layoutBehavior = readLayoutBehavior(p, defaults),
            rawClipboardShortcuts = p[RAW_CLIPBOARD_SHORTCUTS] ?: defaults.rawClipboardShortcuts,
            longPressLetterActions = readLongPressLetterActions(p, defaults),
            emojiToolbar = p[EMOJI_TOOLBAR] ?: defaults.emojiToolbar,
            coloredToolIcons = p[COLORED_TOOL_ICONS] ?: defaults.coloredToolIcons,
            toolColorOverrides = decodeToolColors(p[TOOL_COLOR_OVERRIDES]),
            toolIconGradients = p[TOOL_ICON_GRADIENTS] ?: defaults.toolIconGradients,
            toolColorEndOverrides = decodeToolColors(p[TOOL_COLOR_END_OVERRIDES]),
            icons = readIcons(p, defaults),
            incognito = p[INCOGNITO] ?: defaults.incognito,
            // Empty stored string is a valid state (everything in the toolbox),
            // distinct from never-set (defaults apply).
            toolbarTools = readToolbarTools(p, defaults),
            toolbarBehavior = readToolbarBehavior(p, defaults),
            toolbarHeightDp = p[TOOLBAR_HEIGHT] ?: defaults.toolbarHeightDp,
            toolbarLabels = p[TOOLBAR_LABELS] ?: defaults.toolbarLabels,
            toolbarLabelSize = p[TOOLBAR_LABEL_SIZE] ?: defaults.toolbarLabelSize,
            toolCircleRadiusDp = p[TOOL_CIRCLE_RADIUS] ?: defaults.toolCircleRadiusDp,
            toolShape = p[TOOL_SHAPE]
                ?.let { runCatching { KeyShapeKind.valueOf(it) }.getOrNull() }
                ?: defaults.toolShape,
            commaAsEmoji = p[COMMA_AS_EMOJI] ?: defaults.commaAsEmoji,
            swapCommaAndGlobe = p[SWAP_COMMA_GLOBE] ?: defaults.swapCommaAndGlobe,
            emojiTabMode = p[EMOJI_TAB_MODE]
                ?.let { runCatching { EmojiTabMode.valueOf(it) }.getOrNull() }
                ?: defaults.emojiTabMode,
            emojiClearRecentsButton = p[EMOJI_CLEAR_RECENTS_BUTTON] ?: defaults.emojiClearRecentsButton,
            emojiLongPressName = p[EMOJI_LONG_PRESS_NAME] ?: defaults.emojiLongPressName,
            emojiPrediction = p[EMOJI_PREDICTION] ?: defaults.emojiPrediction,
            emojiBarMode = p[EMOJI_BAR_MODE]
                ?.let { runCatching { EmojiBarMode.valueOf(it) }.getOrNull() }
                ?: defaults.emojiBarMode,
            emojiBarContent = p[EMOJI_BAR_CONTENT]
                ?.let { runCatching { EmojiBarContent.valueOf(it) }.getOrNull() }
                ?: defaults.emojiBarContent,
            emojiInsertMode = p[EMOJI_INSERT_MODE]
                ?.let { runCatching { EmojiInsertMode.valueOf(it) }.getOrNull() }
                ?: defaults.emojiInsertMode,
            emoji = readEmoji(p, defaults),
            enabledTools = ToolbarTool.entries - decodeDisabledTools(p[DISABLED_TOOLS]),
            toolboxOrder = decodeToolOrder(p[TOOLBOX_ORDER]),
            toolboxHintDismissed = p[TOOLBOX_HINT_DISMISSED] ?: defaults.toolboxHintDismissed,
            toolbox = readToolbox(p, defaults),
            sensorTools = readSensorTools(p, defaults),
            redoUsesCtrlY = p[REDO_USES_CTRL_Y] ?: defaults.redoUsesCtrlY,
            networkLog = readNetworkLog(p, defaults),
            weather = readWeather(p, defaults),
            calendarTool = readCalendarTool(p, defaults),
            handwritingStylusOnly = p[HANDWRITING_STYLUS_ONLY] ?: defaults.handwritingStylusOnly,
            handwritingCommitDelayMs = p[HANDWRITING_COMMIT_DELAY]
                ?: defaults.handwritingCommitDelayMs,
            handwritingAutoSpace = p[HANDWRITING_AUTO_SPACE] ?: defaults.handwritingAutoSpace,
            voiceBar = readVoiceBar(p, defaults),
            voiceContinuous = p[VOICE_CONTINUOUS] ?: defaults.voiceContinuous,
            voiceSpokenPunctuation = p[VOICE_SPOKEN_PUNCTUATION]
                ?: defaults.voiceSpokenPunctuation,
            whisper = readWhisper(p, defaults),
            camera = readCamera(p, defaults),
            stickerSendMode = p[STICKER_SEND_MODE]
                ?.let { runCatching { MediaSendMode.valueOf(it) }.getOrNull() }
                ?: defaults.stickerSendMode,
            scanner = readScanner(p, defaults),
            gif = readGif(p, defaults),
            dictionaryAutoLookup = p[DICTIONARY_AUTO_LOOKUP] ?: defaults.dictionaryAutoLookup,
            dictionarySources = p[DICTIONARY_SOURCES]?.let(DictionarySources::decode) ?: defaults.dictionarySources,
            textEditing = readTextEditing(p, defaults),
            trackpad = readTrackpad(p, defaults),
            vocabulary = readVocabulary(p, defaults),
            powerSaving = readPowerSaving(p, defaults),
            dataSaver = dataSaverFromPrefs(p, defaults),
            numpadCalculatorLayout = p[NUMPAD_CALCULATOR_LAYOUT]
                ?: p[NUMPAD_PHONE_LAYOUT]?.not()
                ?: defaults.numpadCalculatorLayout,
            incognitoPausesClipboard = p[INCOGNITO_PAUSES_CLIPBOARD] ?: defaults.incognitoPausesClipboard,
            incognitoPausesLearning = p[INCOGNITO_PAUSES_LEARNING] ?: defaults.incognitoPausesLearning,
            autoIncognito = p[AUTO_INCOGNITO] ?: defaults.autoIncognito,
            cloudBackup = p[CloudBackup.KEY] ?: defaults.cloudBackup,
            currencyDecimals = p[CURRENCY_DECIMALS] ?: defaults.currencyDecimals,
            currencyLabel = p[CURRENCY_LABEL]
                ?.let { runCatching { CurrencyLabel.valueOf(it) }.getOrNull() }
                ?: defaults.currencyLabel,
            currencyCacheHours = p[CURRENCY_CACHE_HOURS] ?: defaults.currencyCacheHours,
            rateSources = readRateSources(p, defaults),
            grammarDebounceMs = p[GRAMMAR_DEBOUNCE_MS] ?: defaults.grammarDebounceMs,
            unitConvertLast = p[UNIT_CONVERT_LAST] ?: defaults.unitConvertLast,
            compoundUnits = p[COMPOUND_UNITS] ?: defaults.compoundUnits,
            toolboxColumns = p[TOOLBOX_COLUMNS] ?: defaults.toolboxColumns,
            translateTargetLang = p[TRANSLATE_TARGET_LANG] ?: defaults.translateTargetLang,
            translate = readTranslate(p, defaults),
            grammarDialect = p[GRAMMAR_DIALECT]
                ?.let { runCatching { GrammarDialect.valueOf(it) }.getOrNull() }
                ?: defaults.grammarDialect,
            grammarHiddenKinds = readGrammarHiddenKinds(p, defaults),
            spellCheckerNoSuggestions = p[SPELL_CHECKER_NO_SUGGESTIONS]
                ?: defaults.spellCheckerNoSuggestions,
            translateApiKey = p[TRANSLATE_API_KEY] ?: defaults.translateApiKey,
            webSearch = readWebSearch(p, defaults),
            symbolRecents = p[SYMBOL_RECENTS]?.split('\t')?.filter { it.isNotEmpty() }
                ?: defaults.symbolRecents,
            symbolRowEnabled = p[SYMBOL_ROW_ENABLED] ?: defaults.symbolRowEnabled,
            symbolRowSetIds = p[SYMBOL_ROW_SETS]?.split('\t')?.filter { it.isNotEmpty() }
                ?.ifEmpty { null } ?: defaults.symbolRowSetIds,
            symbolRowActiveSetId = p[SYMBOL_ROW_ACTIVE_SET] ?: defaults.symbolRowActiveSetId,
            customSymbolSets = p[CUSTOM_SYMBOL_SETS]?.let { SymbolSetCodec.decodeList(it) }
                ?: defaults.customSymbolSets,
            // Never stored: honor the legacy emoji-row position toggle so
            // existing users keep their arrangement.
            barOrder = readBarOrder(p, defaults),
            emojiFullBleed = p[EMOJI_FULL_BLEED] ?: defaults.emojiFullBleed,
            mediaFullBleed = p[MEDIA_FULL_BLEED] ?: defaults.mediaFullBleed,
            modeToolOrderEdits = p[MODE_TOOL_ORDER_EDITS] ?: defaults.modeToolOrderEdits,
            modeToolOrderHintSeen = p[MODE_TOOL_ORDER_HINT] ?: defaults.modeToolOrderHintSeen,
            modesEnabled = p[MODES_ENABLED] ?: defaults.modesEnabled,
            keyboardModes = p[KEYBOARD_MODES]?.let { KeyboardModeCodec.decodeList(it) }
                ?: defaults.keyboardModes,
            smartSuggestions = p[SMART_SUGGESTIONS] ?: defaults.smartSuggestions,
            smartCalc = p[SMART_CALC] ?: defaults.smartCalc,
            smartCurrency = p[SMART_CURRENCY] ?: defaults.smartCurrency,
            smartUnits = p[SMART_UNITS] ?: defaults.smartUnits,
            smartToolKeywords = p[SMART_TOOL_KEYWORDS] ?: defaults.smartToolKeywords,
            smartChips = readSmartChips(p, defaults),
            selectionMacros = readSelectionMacros(p, defaults),
            toolKeywords = p[TOOL_KEYWORDS] ?: defaults.toolKeywords,
            toolKeywordCase = p[TOOL_KEYWORD_CASE] ?: defaults.toolKeywordCase,
            calcDegrees = p[CALC_DEGREES] ?: defaults.calcDegrees,
            calcPhoneLayout = p[CALC_PHONE_LAYOUT] ?: defaults.calcPhoneLayout,
            calcPrecision = p[CALC_PRECISION] ?: defaults.calcPrecision,
            currencyFrom = p[CURRENCY_FROM] ?: defaults.currencyFrom,
            currencyTo = p[CURRENCY_TO] ?: defaults.currencyTo,
            // The keys stay flat across the grouping, so a user's stored
            // generator settings survive the refactor untouched.
            passwordGenerator = readPasswordGenerator(p, defaults),
            typingTest = readTypingTest(p, defaults),
            typingStatsEnabled = p[TYPING_STATS_ENABLED] ?: defaults.typingStatsEnabled,
            statsVersion = p[STATS_VERSION] ?: defaults.statsVersion,
            ai = readAi(p, defaults),
            launcher = readLauncher(p, defaults),
            mediaControl = readMediaControl(p, defaults),
            kdeConnect = readKdeConnect(p, defaults),
            selfHosted = readSelfHosted(p, defaults),
        )
    }

    // ---- mapPreferences, one settings family per function ----
    //
    // Split out of mapPreferences so that no one method passes ART's
    // huge-method limit (10,000 dex instructions). As one expression it was
    // ~25,700 and was never compiled, AOT or JIT: every settings emission,
    // the keyboard's first one included, ran it in the interpreter.

    private fun readAutoTheme(p: Preferences, defaults: KeyboardSettings) =
        AutoThemeSettings(
            enabled = p[AUTO_THEME_ENABLED] ?: defaults.autoTheme.enabled,
            lightThemeId = p[AUTO_THEME_LIGHT_ID] ?: defaults.autoTheme.lightThemeId,
            darkThemeId = p[AUTO_THEME_DARK_ID] ?: defaults.autoTheme.darkThemeId,
            trigger = p[AUTO_THEME_TRIGGER]
                ?.let { runCatching { AutoThemeTrigger.valueOf(it) }.getOrNull() }
                ?: defaults.autoTheme.trigger,
            dayStartMinutes = p[AUTO_THEME_DAY_START] ?: defaults.autoTheme.dayStartMinutes,
            nightStartMinutes = p[AUTO_THEME_NIGHT_START] ?: defaults.autoTheme.nightStartMinutes,
            lightRandom = p[AUTO_THEME_LIGHT_RANDOM] ?: defaults.autoTheme.lightRandom,
            darkRandom = p[AUTO_THEME_DARK_RANDOM] ?: defaults.autoTheme.darkRandom,
            lightPoolIds = p[AUTO_THEME_LIGHT_POOL] ?: defaults.autoTheme.lightPoolIds,
            darkPoolIds = p[AUTO_THEME_DARK_POOL] ?: defaults.autoTheme.darkPoolIds,
            shuffleInterval = p[AUTO_THEME_SHUFFLE_INTERVAL]
                ?.let { runCatching { RotationInterval.valueOf(it) }.getOrNull() }
                ?: defaults.autoTheme.shuffleInterval,
            shuffleLightId = p[AUTO_THEME_SHUFFLE_LIGHT_ID]
                ?: defaults.autoTheme.shuffleLightId,
            shuffleDarkId = p[AUTO_THEME_SHUFFLE_DARK_ID] ?: defaults.autoTheme.shuffleDarkId,
            shuffledAtEpochMs = p[AUTO_THEME_SHUFFLED_AT]
                ?: defaults.autoTheme.shuffledAtEpochMs,
            shuffledAtElapsedMs = p[AUTO_THEME_SHUFFLED_AT_ELAPSED]
                ?: defaults.autoTheme.shuffledAtElapsedMs,
        )

    private fun readPhotoBackground(p: Preferences, defaults: KeyboardSettings) =
        PhotoBackgroundSettings(
            unsplashApiKey = p[PHOTO_UNSPLASH_KEY] ?: defaults.photoBackground.unsplashApiKey,
            pexelsApiKey = p[PHOTO_PEXELS_KEY] ?: defaults.photoBackground.pexelsApiKey,
            rotateEnabled = p[PHOTO_ROTATE_ENABLED] ?: defaults.photoBackground.rotateEnabled,
            interval = p[PHOTO_ROTATE_INTERVAL]
                ?.let { runCatching { RotationInterval.valueOf(it) }.getOrNull() }
                ?: defaults.photoBackground.interval,
            scope = p[PHOTO_ROTATE_SCOPE]
                ?.let { runCatching { RotationScope.valueOf(it) }.getOrNull() }
                ?: defaults.photoBackground.scope,
            scopeThemeIds = p[PHOTO_ROTATE_SCOPE_THEMES] ?: defaults.photoBackground.scopeThemeIds,
            // An unknown name is dropped rather than failing the whole set,
            // so a build that adds a source stays readable by an older one.
            sources = p[PHOTO_ROTATE_SOURCES]
                ?.mapNotNull { name -> runCatching { RotationSourceKind.valueOf(name) }.getOrNull() }
                ?.toSet()
                ?: defaults.photoBackground.sources,
            // Tab-joined, the same shape `symbol_recents` uses; a tab is
            // stripped from a search term on the way in.
            topics = p[PHOTO_ROTATE_TOPICS]?.split('\t')?.filter { it.isNotEmpty() }
                ?: defaults.photoBackground.topics,
            queries = p[PHOTO_ROTATE_QUERIES]?.split('\t')?.filter { it.isNotEmpty() }
                ?: defaults.photoBackground.queries,
            landscapeOnly = p[PHOTO_LANDSCAPE_ONLY] ?: defaults.photoBackground.landscapeOnly,
            safeSearch = p[PHOTO_SAFE_SEARCH] ?: defaults.photoBackground.safeSearch,
            fetchOnMetered = p[PHOTO_FETCH_ON_METERED] ?: defaults.photoBackground.fetchOnMetered,
            poolTarget = (p[PHOTO_POOL_TARGET] ?: defaults.photoBackground.poolTarget)
                .coerceIn(
                    PhotoBackgroundSettings.MIN_POOL_TARGET,
                    PhotoBackgroundSettings.MAX_POOL_TARGET,
                ),
            seedPalette = p[PHOTO_SEED_PALETTE] ?: defaults.photoBackground.seedPalette,
            keyOpacity = p[PHOTO_KEY_OPACITY] ?: defaults.photoBackground.keyOpacity,
            poolBudgetMb = p[PHOTO_POOL_BUDGET_MB] ?: defaults.photoBackground.poolBudgetMb,
            readabilityGuard = p[PHOTO_READABILITY_GUARD]
                ?: defaults.photoBackground.readabilityGuard,
        )

    private fun readSizingOverrides(p: Preferences, defaults: KeyboardSettings) =
        ScreenVariant.entries
            .filter { it.isOverride }
            .associateWith { v ->
                SizingOverride(
                    keyHeightDp = p[keyHeightKey(v)],
                    numberRowHeightDp = p[numberRowHeightKey(v)],
                    bottomPaddingDp = p[bottomPaddingKey(v)],
                    keyboardWidthPercent = p[widthPercentKey(v)],
                    fontScale = p[fontScaleKey(v)],
                    keyboardAlignment = p[alignmentKey(v)]
                        ?.let { name -> runCatching { KeyboardAlignment.valueOf(name) }.getOrNull() },
                    keyboardScale = p[keyboardScaleKey(v)],
                    keyGapScale = p[keyGapScaleKey(v)],
                    sidePadLeftScale = p[sidePadLeftScaleKey(v)] ?: p[sidePadScaleKey(v)],
                    sidePadRightScale = p[sidePadRightScaleKey(v)] ?: p[sidePadScaleKey(v)],
                    bottomRowHeightDp = p[bottomRowHeightKey(v)],
                    numberRow = p[variantNumberRowKey(v)],
                )
            }
            .filterValues { !it.isEmpty }

    private fun readHaptics(p: Preferences, defaults: KeyboardSettings) =
        HapticSettings(
            enabled = p[HAPTIC] ?: defaults.haptics.enabled,
            strengthMs = p[HAPTIC_STRENGTH] ?: defaults.haptics.strengthMs,
            amplitude = p[HAPTIC_AMPLITUDE] ?: defaults.haptics.amplitude,
            style = p[HAPTIC_STYLE]?.let { runCatching { HapticStyle.valueOf(it) }.getOrNull() }
                ?: defaults.haptics.style,
            onLongPress = p[HAPTIC_ON_LONG_PRESS] ?: defaults.haptics.onLongPress,
            onLongPressRelease = p[HAPTIC_ON_LONG_PRESS_RELEASE]
                ?: defaults.haptics.onLongPressRelease,
        )

    private fun readFeedback(p: Preferences, defaults: KeyboardSettings) =
        FeedbackSettings(
            vibrateOnSpace = p[FEEDBACK_VIBRATE_SPACE] ?: defaults.feedback.vibrateOnSpace,
            vibrateOnDeleteSwipe = p[FEEDBACK_VIBRATE_DELETE_SWIPE]
                ?: defaults.feedback.vibrateOnDeleteSwipe,
            vibrateOnRepeat = p[FEEDBACK_VIBRATE_REPEAT] ?: defaults.feedback.vibrateOnRepeat,
            soundOnRepeat = p[FEEDBACK_SOUND_REPEAT] ?: defaults.feedback.soundOnRepeat,
            respectSystemTouchFeedback = p[FEEDBACK_RESPECT_SYSTEM_TOUCH]
                ?: defaults.feedback.respectSystemTouchFeedback,
            toastOnCopy = p[FEEDBACK_TOAST_ON_COPY] ?: defaults.feedback.toastOnCopy,
            hapticsRespectDnd = p[FEEDBACK_HAPTICS_RESPECT_DND]
                ?: defaults.feedback.hapticsRespectDnd,
        )

    private fun readSound(p: Preferences, defaults: KeyboardSettings) =
        KeySoundSettings(
            enabled = p[KEY_SOUND] ?: defaults.sound.enabled,
            style = p[KEY_SOUND_STYLE]
                ?.let { runCatching { KeySoundStyle.valueOf(it) }.getOrNull() }
                ?: defaults.sound.style,
            volume = p[KEY_SOUND_VOLUME] ?: defaults.sound.volume,
            customId = p[KEY_SOUND_CUSTOM_ID] ?: defaults.sound.customId,
            packId = p[KEY_SOUND_PACK_ID] ?: defaults.sound.packId,
            playRelease = p[KEY_SOUND_RELEASE] ?: defaults.sound.playRelease,
        )

    private fun readAccessibility(p: Preferences, defaults: KeyboardSettings) =
        AccessibilitySettings(
            colorVision = p[COLOR_VISION_FILTER]
                ?.let { runCatching { ColorVisionFilter.valueOf(it) }.getOrNull() }
                ?: defaults.accessibility.colorVision,
            highContrast = p[HIGH_CONTRAST_KEYS] ?: defaults.accessibility.highContrast,
            keyOutlines = p[KEY_OUTLINES] ?: defaults.accessibility.keyOutlines,
            boldLabels = p[BOLD_KEY_LABELS] ?: defaults.accessibility.boldLabels,
            screenReader = p[SCREEN_READER_MODE]
                ?.let { runCatching { ScreenReaderMode.valueOf(it) }.getOrNull() }
                ?: defaults.accessibility.screenReader,
            keyDebounceMs = p[KEY_DEBOUNCE_MS] ?: defaults.accessibility.keyDebounceMs,
        )

    private fun readCorrection(p: Preferences, defaults: KeyboardSettings) =
        AutocorrectSettings(
            enabled = p[AUTOCORRECT] ?: defaults.correction.enabled,
            confidence = p[AUTOCORRECT_CONFIDENCE] ?: defaults.correction.confidence,
            adaptive = p[AUTOCORRECT_ADAPTIVE] ?: defaults.correction.adaptive,
            revertOnBackspace = p[REVERT_AUTOCORRECT_ON_BACKSPACE]
                ?: defaults.correction.revertOnBackspace,
            undoMemory = p[AUTOCORRECT_UNDO_MEMORY]
                ?.let { runCatching { UndoMemory.valueOf(it) }.getOrNull() }
                ?: defaults.correction.undoMemory,
            skipAllCaps = p[AUTOCORRECT_SKIP_ALL_CAPS] ?: defaults.correction.skipAllCaps,
        )

    private fun readAutoText(p: Preferences, defaults: KeyboardSettings) =
        AutoTextSettings(
            apostrophe = p[AUTO_APOSTROPHE] ?: defaults.autoText.apostrophe,
            capitalize = p[AUTO_CAPITALIZE] ?: defaults.autoText.capitalize,
            doubleSpacePeriod = p[DOUBLE_SPACE_PERIOD] ?: defaults.autoText.doubleSpacePeriod,
            doubleSpaceTab = p[DOUBLE_SPACE_TAB] ?: defaults.autoText.doubleSpaceTab,
            spaceAfterPunctuation = p[AUTO_SPACE_AFTER_PUNCTUATION]
                ?: defaults.autoText.spaceAfterPunctuation,
            hugPunctuation = p[HUG_PUNCTUATION] ?: defaults.autoText.hugPunctuation,
            hugPunctuationMarks = p[HUG_PUNCTUATION_MARKS]?.takeIf { it.isNotBlank() }
                ?: defaults.autoText.hugPunctuationMarks,
            languagePunctuationSpacing = p[LANGUAGE_PUNCTUATION_SPACING]
                ?: defaults.autoText.languagePunctuationSpacing,
        )

    private fun readSuggestionSources(p: Preferences, defaults: KeyboardSettings) =
        SuggestionSourceSettings(
            inAllFields = p[SHOW_SUGGESTIONS_ALL_FIELDS]
                ?: defaults.suggestionSources.inAllFields,
            contacts = p[CONTACT_SUGGESTIONS] ?: defaults.suggestionSources.contacts,
            contactEmails = p[CONTACT_EMAIL_SUGGESTIONS]
                ?: defaults.suggestionSources.contactEmails,
            contactEmailsInEmailFields = p[CONTACT_EMAIL_SUGGESTIONS_IN_EMAIL_FIELDS]
                ?: defaults.suggestionSources.contactEmailsInEmailFields,
            appNames = p[APP_NAME_SUGGESTIONS] ?: defaults.suggestionSources.appNames,
            blacklist = p[SUGGESTION_BLACKLIST] ?: defaults.suggestionSources.blacklist,
            blacklistByLanguage = blacklistsByLanguage(p),
            blacklistScope = p[SUGGESTION_BLACKLIST_SCOPE]
                ?.let { runCatching { BlacklistScope.valueOf(it) }.getOrNull() }
                ?: defaults.suggestionSources.blacklistScope,
            inlineEmojiSearch = p[INLINE_EMOJI_SEARCH]
                ?: defaults.suggestionSources.inlineEmojiSearch,
            inlineAutofill = p[INLINE_AUTOFILL] ?: defaults.suggestionSources.inlineAutofill,
        )

    private fun readGesture(p: Preferences, defaults: KeyboardSettings) =
        GestureSettings(
            spaceGlideMultiWord = p[GESTURE_SPACE_MULTI_WORD] ?: defaults.gesture.spaceGlideMultiWord,
            shiftGlideCapitals = p[GESTURE_SHIFT_CAPITALS] ?: defaults.gesture.shiftGlideCapitals,
            shiftGlideMode = p[GESTURE_SHIFT_MODE]
                ?.let { runCatching { ShiftGlideMode.valueOf(it) }.getOrNull() }
                ?: defaults.gesture.shiftGlideMode,
            ambiguityPicker = p[GESTURE_AMBIGUITY_PICKER] ?: defaults.gesture.ambiguityPicker,
            // Coerced on the way in as well as on the way out: a value
            // restored from an edited backup must never index past the
            // picker's target array.
            pickerDwellMs = (p[GESTURE_PICKER_DWELL_MS] ?: defaults.gesture.pickerDwellMs)
                .coerceIn(GlidePickerDwellMsRange),
            pickerSensitivity = p[GESTURE_PICKER_SENSITIVITY]
                ?.let { runCatching { GlidePickerSensitivity.valueOf(it) }.getOrNull() }
                ?: defaults.gesture.pickerSensitivity,
            pickerHoldToAsk = p[GESTURE_PICKER_HOLD_TO_ASK] ?: defaults.gesture.pickerHoldToAsk,
            pickerChoices = (p[GESTURE_PICKER_CHOICES] ?: defaults.gesture.pickerChoices)
                .coerceIn(GlidePickerChoicesRange),
            apostropheKey = p[GESTURE_APOSTROPHE_KEY]
                ?.let { runCatching { GlideApostropheKey.valueOf(it) }.getOrNull() }
                ?: defaults.gesture.apostropheKey,
            possessiveKey = p[GESTURE_POSSESSIVE_KEY]
                ?.let { runCatching { GlideApostropheKey.valueOf(it) }.getOrNull() }
                ?: legacyPossessiveKey(p, defaults),
            autoSpaceAfterGlide = p[GESTURE_AUTO_SPACE] ?: defaults.gesture.autoSpaceAfterGlide,
            startThresholdSlop = p[GESTURE_START_THRESHOLD_SLOP] ?: defaults.gesture.startThresholdSlop,
            postTypeCooldownMs = p[GESTURE_POST_TYPE_COOLDOWN_MS] ?: defaults.gesture.postTypeCooldownMs,
            handwriteDotCooldownMs = p[GESTURE_HANDWRITE_DOT_COOLDOWN_MS] ?: defaults.gesture.handwriteDotCooldownMs,
            trailWidthDp = p[GESTURE_TRAIL_WIDTH_DP] ?: defaults.gesture.trailWidthDp,
            trailDurationMs = p[GESTURE_TRAIL_DURATION_MS] ?: defaults.gesture.trailDurationMs,
            trailOpacity = p[GESTURE_TRAIL_OPACITY] ?: defaults.gesture.trailOpacity,
            wordPreview = p[GESTURE_WORD_PREVIEW] ?: defaults.gesture.wordPreview,
            wordPreviewOffsetYDp = p[GESTURE_WORD_PREVIEW_OFFSET_Y]
                ?: defaults.gesture.wordPreviewOffsetYDp,
            wordPreviewOffsetXDp = p[GESTURE_WORD_PREVIEW_OFFSET_X]
                ?: defaults.gesture.wordPreviewOffsetXDp,
            wordPreviewFontSp = p[GESTURE_WORD_PREVIEW_FONT_SP]
                ?: defaults.gesture.wordPreviewFontSp,
            wordPreviewBackground = p[GESTURE_WORD_PREVIEW_BACKGROUND]
                ?: defaults.gesture.wordPreviewBackground,
            wordPreviewTextColor = p[GESTURE_WORD_PREVIEW_TEXT_COLOR]
                ?: defaults.gesture.wordPreviewTextColor,
            stripPreviewOnly = p[GESTURE_STRIP_PREVIEW_ONLY]
                ?: defaults.gesture.stripPreviewOnly,
            vocabulary = p[GESTURE_VOCABULARY]
                ?.let { runCatching { GlideVocabulary.valueOf(it) }.getOrNull() }
                ?: defaults.gesture.vocabulary,
            sandbox = p[GESTURE_SANDBOX]
                ?.let { runCatching { GlideSandbox.valueOf(it) }.getOrNull() }
                ?: defaults.gesture.sandbox,
            previewSteadiness = p[GESTURE_PREVIEW_STEADINESS]
                ?.let { runCatching { GlidePreviewSteadiness.valueOf(it) }.getOrNull() }
                ?: defaults.gesture.previewSteadiness,
            lookAhead = p[GESTURE_LOOK_AHEAD]
                ?.let { runCatching { GlideLookAhead.valueOf(it) }.getOrNull() }
                ?: defaults.gesture.lookAhead,
            commitColor = p[GESTURE_COMMIT_COLOR]
                ?.let { runCatching { GlideCommitColor.valueOf(it) }.getOrNull() }
                ?: defaults.gesture.commitColor,
            commitColorScope = p[GESTURE_COMMIT_COLOR_SCOPE]
                ?.let { runCatching { GlideCommitColorScope.valueOf(it) }.getOrNull() }
                ?: defaults.gesture.commitColorScope,
            startRadius = p[GESTURE_START_RADIUS] ?: defaults.gesture.startRadius,
            endRadius = p[GESTURE_END_RADIUS] ?: defaults.gesture.endRadius,
            nearRadius = p[GESTURE_NEAR_RADIUS] ?: defaults.gesture.nearRadius,
            dwellFull = p[GESTURE_DWELL_FULL] ?: defaults.gesture.dwellFull,
            loopDouble = p[GESTURE_LOOP_DOUBLE] ?: defaults.gesture.loopDouble,
            loopMinArc = p[GESTURE_LOOP_MIN_ARC] ?: defaults.gesture.loopMinArc,
            loopExtent = p[GESTURE_LOOP_EXTENT] ?: defaults.gesture.loopExtent,
            loopRadius = p[GESTURE_LOOP_RADIUS] ?: defaults.gesture.loopRadius,
            wiggleDouble = p[GESTURE_WIGGLE_DOUBLE] ?: defaults.gesture.wiggleDouble,
            wiggleExtent = p[GESTURE_WIGGLE_EXTENT] ?: defaults.gesture.wiggleExtent,
            wiggleWeight = p[GESTURE_WIGGLE_WEIGHT] ?: defaults.gesture.wiggleWeight,
            learnSwipeStyle = p[GESTURE_LEARN_SWIPE_STYLE] ?: defaults.gesture.learnSwipeStyle,
            shapesPerWord = (p[GESTURE_SHAPES_PER_WORD] ?: defaults.gesture.shapesPerWord)
                .coerceIn(GlideShapesPerWordRange),
            searchAllChip = p[GESTURE_SEARCH_ALL_CHIP] ?: defaults.gesture.searchAllChip,
            swipeStyleVersion = p[GESTURE_SWIPE_STYLE_VERSION] ?: defaults.gesture.swipeStyleVersion,
        )

    private fun readHardwareKeyboard(p: Preferences, defaults: KeyboardSettings) =
        HardwareKeyboardSettings(
            shortcutsEnabled = p[HW_SHORTCUTS_ENABLED] ?: defaults.hardwareKeyboard.shortcutsEnabled,
            panelNavigation = p[HW_PANEL_NAVIGATION] ?: defaults.hardwareKeyboard.panelNavigation,
            dpadKeyNavigation = p[HW_DPAD_KEY_NAVIGATION]
                ?: defaults.hardwareKeyboard.dpadKeyNavigation,
            dpadKeyNavigationUntouched = p[HW_DPAD_KEY_NAVIGATION] == null,
            escClosesPanel = p[HW_ESC_CLOSES_PANEL] ?: defaults.hardwareKeyboard.escClosesPanel,
            suggestionHotkeys = p[HW_SUGGESTION_HOTKEYS]
                ?.let { raw -> runCatching { SuggestionHotkeyMode.valueOf(raw) }.getOrNull() }
                ?: defaults.hardwareKeyboard.suggestionHotkeys,
            suggestionHintsAlways = p[HW_SUGGESTION_HINTS_ALWAYS]
                ?: defaults.hardwareKeyboard.suggestionHintsAlways,
            toolbarDigitChord = p[HW_TOOLBAR_DIGIT_CHORD]
                ?: defaults.hardwareKeyboard.toolbarDigitChord,
            macShortcuts = p[HW_MAC_SHORTCUTS] ?: defaults.hardwareKeyboard.macShortcuts,
            languageSwitchChord = p[HW_LANGUAGE_SWITCH_CHORD]
                ?: defaults.hardwareKeyboard.languageSwitchChord,
            hintModifierWords = p[HW_HINT_MODIFIER_WORDS]
                ?: defaults.hardwareKeyboard.hintModifierWords,
            autoShowUi = p[HW_AUTO_SHOW_UI] ?: defaults.hardwareKeyboard.autoShowUi,
            leader = p[HW_LEADER] ?: defaults.hardwareKeyboard.leader,
            pickerTimeoutMs = p[HW_PICKER_TIMEOUT_MS] ?: defaults.hardwareKeyboard.pickerTimeoutMs,
            // Absent, not empty, means "never edited": an empty stored map is
            // a user who unbound every letter, and must stay empty.
            toolByLetter = p[HW_TOOL_LETTERS]?.let(::decodeToolLetters)
                ?: defaults.hardwareKeyboard.toolByLetter,
        )

    private fun readPerAppLanguage(p: Preferences, defaults: KeyboardSettings) =
        PerAppLanguageSettings(
            enabled = p[PER_APP_LANGUAGE_ENABLED] ?: defaults.perAppLanguage.enabled,
            layoutByPackage = p[PER_APP_LAYOUT_MAP]?.let { decodePerAppLayouts(it) }
                ?: defaults.perAppLanguage.layoutByPackage,
        )

    private fun readOnboarding(p: Preferences, defaults: KeyboardSettings) =
        OnboardingSettings(
            personaLanguages = p[ONBOARDING_PERSONA_LANGUAGES]
                ?.let { runCatching { PersonaLanguages.valueOf(it) }.getOrNull() }
                ?: defaults.onboarding.personaLanguages,
            personaDepth = p[ONBOARDING_PERSONA_DEPTH]
                ?.let { runCatching { PersonaDepth.valueOf(it) }.getOrNull() }
                ?: defaults.onboarding.personaDepth,
            personaPrivacy = p[ONBOARDING_PERSONA_PRIVACY]
                ?.let { runCatching { PersonaPrivacy.valueOf(it) }.getOrNull() }
                ?: defaults.onboarding.personaPrivacy,
        )

    private fun readAppUi(p: Preferences, defaults: KeyboardSettings) =
        AppUiSettings(
            themeGalleryStyle = p[THEME_GALLERY_STYLE]
                ?.let { runCatching { ThemeGalleryStyle.valueOf(it) }.getOrNull() }
                ?: defaults.appUi.themeGalleryStyle,
            advancedOpen = p[ADVANCED_OPEN] ?: defaults.appUi.advancedOpen,
            defaultWordlistSize = p[DEFAULT_WORDLIST_SIZE]
                ?.let {
                    runCatching { DictionaryCatalog.DictionarySize.valueOf(it) }.getOrNull()
                }
                ?: defaults.appUi.defaultWordlistSize,
            dictionarySort = p[DICTIONARY_SORT]
                ?.let { runCatching { DictionarySort.valueOf(it) }.getOrNull() }
                ?: defaults.appUi.dictionarySort,
            rowIcons = p[SETTINGS_ROW_ICONS] ?: defaults.appUi.rowIcons,
            screenTransitions = p[SETTINGS_SCREEN_TRANSITIONS]
                ?: defaults.appUi.screenTransitions,
        )

    private fun readToolLimits(p: Preferences, defaults: KeyboardSettings) =
        ToolLimitSettings(
            weatherRefreshMinutes = p[WEATHER_REFRESH_MINUTES]
                ?: defaults.toolLimits.weatherRefreshMinutes,
            wikiLinkLimit = p[WIKI_LINK_LIMIT] ?: defaults.toolLimits.wikiLinkLimit,
            qrMaxChars = p[QR_MAX_CHARS] ?: defaults.toolLimits.qrMaxChars,
            passwordSymbols = p[PASSWORD_SYMBOLS] ?: defaults.toolLimits.passwordSymbols,
        )

    private fun readRows(p: Preferences, defaults: KeyboardSettings) =
        RowSettings(
            symbolRowHeightDp = p[SYMBOL_ROW_HEIGHT] ?: defaults.rows.symbolRowHeightDp,
            // Clamped on the way in as well as on the way out: a value
            // outside the range is a row the screen cannot draw.
            symbolRowLines = p[SYMBOL_ROW_LINES]
                ?.coerceIn(SymbolRowLinesRange.first, SymbolRowLinesRange.last)
                ?: defaults.rows.symbolRowLines,
            symbolRowScroll = p[SYMBOL_ROW_SCROLL]
                ?.let { runCatching { SymbolRowScroll.valueOf(it) }.getOrNull() }
                ?: defaults.rows.symbolRowScroll,
            manualModeDuration = p[MANUAL_MODE_DURATION]
                ?.let { runCatching { ManualModeDuration.valueOf(it) }.getOrNull() }
                ?: defaults.rows.manualModeDuration,
            dictionaryBarEnabled = p[DICTIONARY_BAR_ENABLED] ?: defaults.rows.dictionaryBarEnabled,
            dictionaryBarFilter = p[DICTIONARY_BAR_FILTER] ?: defaults.rows.dictionaryBarFilter,
        )

    private fun readCjk(p: Preferences, defaults: KeyboardSettings) =
        CjkSettings(
            pinyinFuzzy = p[PINYIN_FUZZY] ?: defaults.cjk.pinyinFuzzy,
            // Unknown ids are dropped rather than kept: a pair removed in
            // a later build must not sit in the set forever, and the
            // composer would ignore it anyway.
            pinyinFuzzyPairs = p[PINYIN_FUZZY_PAIRS]
                ?.filterTo(LinkedHashSet()) { it in PinyinFuzzy.ALL_PAIRS }
                ?: defaults.cjk.pinyinFuzzyPairs,
            pinyinDoublePinyin = p[PINYIN_DOUBLE_PINYIN]
                ?.let { runCatching { DoublePinyinScheme.valueOf(it) }.getOrNull() }
                ?: defaults.cjk.pinyinDoublePinyin,
            traditionalOutput = p[CJK_TRADITIONAL_OUTPUT] ?: defaults.cjk.traditionalOutput,
            jyutpingLazy = p[JYUTPING_LAZY] ?: defaults.cjk.jyutpingLazy,
            kanaLooseMarks = p[KANA_LOOSE_MARKS] ?: defaults.cjk.kanaLooseMarks,
            fullWidthSpaceLanguages = p[FULL_WIDTH_SPACE_LANGUAGES] ?: defaults.cjk.fullWidthSpaceLanguages,
            hanRegion = p[CJK_HAN_REGION]
                ?.let { runCatching { HanVariant.HanRegion.valueOf(it) }.getOrNull() }
                ?: defaults.cjk.hanRegion,
        )

    private fun readOneHanded(p: Preferences, defaults: KeyboardSettings) =
        OneHandedSettings(
            portrait = readOneHandedProfile(p, landscape = false, defaults.oneHanded.portrait),
            landscape = readOneHandedProfile(p, landscape = true, defaults.oneHanded.landscape),
        )

    private fun readClipboard(p: Preferences, defaults: KeyboardSettings) =
        ClipboardSettings(
            history = p[CLIPBOARD_HISTORY] ?: defaults.clipboard.history,
            pasteChipSeconds = p[CLIPBOARD_PASTE_CHIP_SECONDS]
                ?: defaults.clipboard.pasteChipSeconds,
            expiryHours = p[CLIPBOARD_EXPIRY_HOURS] ?: defaults.clipboard.expiryHours,
            maxItems = p[CLIPBOARD_MAX_ITEMS] ?: defaults.clipboard.maxItems,
            sensitiveHandling = p[CLIPBOARD_SENSITIVE_HANDLING]
                ?.let { runCatching { SensitiveClipHandling.valueOf(it) }.getOrNull() }
                ?: defaults.clipboard.sensitiveHandling,
            detectSensitive = p[CLIPBOARD_DETECT_SENSITIVE] ?: defaults.clipboard.detectSensitive,
            sensitiveExpiryMinutes = p[CLIPBOARD_SENSITIVE_EXPIRY_MINUTES]
                ?: defaults.clipboard.sensitiveExpiryMinutes,
            linkPreviews = p[CLIPBOARD_LINK_PREVIEWS] ?: defaults.clipboard.linkPreviews,
            trackSource = p[CLIPBOARD_TRACK_SOURCE] ?: defaults.clipboard.trackSource,
            suggestRecent = p[CLIPBOARD_SUGGEST_RECENT] ?: defaults.clipboard.suggestRecent,
            copiedCodeChip = p[CLIPBOARD_COPIED_CODE_CHIP]
                ?.let { runCatching { CopiedCodeChip.valueOf(it) }.getOrNull() }
                ?: p[CLIPBOARD_SUGGEST_CODES_IN_CODE_FIELDS]?.let {
                    if (it) CopiedCodeChip.ANY_FIELD else CopiedCodeChip.OFF
                }
                ?: defaults.clipboard.copiedCodeChip,
            pinnedLast = p[CLIPBOARD_PINNED_LAST] ?: defaults.clipboard.pinnedLast,
            search = p[CLIPBOARD_SEARCH] ?: defaults.clipboard.search,
            userScreenshots = p[CLIPBOARD_USER_SCREENSHOTS] ?: defaults.clipboard.userScreenshots,
            clearAfterPasswordPaste = p[CLIPBOARD_CLEAR_AFTER_PASSWORD_PASTE]
                ?: defaults.clipboard.clearAfterPasswordPaste,
            detectEntities = p[CLIPBOARD_DETECT_ENTITIES] ?: defaults.clipboard.detectEntities,
            phoneFormats = p[CLIPBOARD_PHONE_FORMATS] ?: seededPhoneFormats(),
            fullBleed = p[CLIPBOARD_FULL_BLEED] ?: defaults.clipboard.fullBleed,
            view = p[CLIPBOARD_VIEW]
                ?.let { runCatching { ClipboardView.valueOf(it) }.getOrNull() }
                ?: defaults.clipboard.view,
            showNumbers = p[CLIPBOARD_SHOW_NUMBERS] ?: defaults.clipboard.showNumbers,
            undoDelete = p[CLIPBOARD_UNDO_DELETE] ?: defaults.clipboard.undoDelete,
            swipeToDelete = p[CLIPBOARD_SWIPE_TO_DELETE] ?: defaults.clipboard.swipeToDelete,
            previewLines = p[CLIPBOARD_PREVIEW_LINES]?.coerceIn(ClipPreviewLinesRange)
                ?: defaults.clipboard.previewLines,
            gridColumns = p[CLIPBOARD_GRID_COLUMNS]?.coerceIn(ClipGridColumnsRange)
                ?: defaults.clipboard.gridColumns,
            timeLabel = p[CLIPBOARD_TIME_LABEL]
                ?.let { runCatching { ClipTimeLabel.valueOf(it) }.getOrNull() }
                ?: defaults.clipboard.timeLabel,
            maxTextChars = p[CLIPBOARD_MAX_TEXT_CHARS]?.coerceAtLeast(0)
                ?: defaults.clipboard.maxTextChars,
        )

    private fun readOtp(p: Preferences, defaults: KeyboardSettings) =
        OtpSettings(
            enabled = p[OTP_CHIP_ENABLED] ?: defaults.otp.enabled,
            codeFieldsOnly = p[OTP_CODE_FIELDS_ONLY] ?: defaults.otp.codeFieldsOnly,
            expiryMinutes = p[OTP_EXPIRY_MINUTES] ?: defaults.otp.expiryMinutes,
            dismissNotification = p[OTP_DISMISS_NOTIFICATION]
                ?: defaults.otp.dismissNotification,
            perDigitEntry = p[OTP_PER_DIGIT_ENTRY] ?: defaults.otp.perDigitEntry,
        )

    private fun readAutoBackup(p: Preferences, defaults: KeyboardSettings) =
        AutoBackupSettings(
            enabled = p[AUTO_BACKUP_ENABLED] ?: defaults.autoBackup.enabled,
            destination = p[AUTO_BACKUP_DESTINATION]
                ?.let { id -> BackupDestination.entries.firstOrNull { it.id == id } }
                ?: defaults.autoBackup.destination,
            webDavUrl = p[AUTO_BACKUP_WEBDAV_URL] ?: defaults.autoBackup.webDavUrl,
            webDavUser = p[AUTO_BACKUP_WEBDAV_USER] ?: defaults.autoBackup.webDavUser,
            webDavPassword = p[AUTO_BACKUP_WEBDAV_PASSWORD]
                ?: defaults.autoBackup.webDavPassword,
            s3 = S3Config(
                endpoint = p[AUTO_BACKUP_S3_ENDPOINT] ?: defaults.autoBackup.s3.endpoint,
                region = p[AUTO_BACKUP_S3_REGION] ?: defaults.autoBackup.s3.region,
                bucket = p[AUTO_BACKUP_S3_BUCKET] ?: defaults.autoBackup.s3.bucket,
                prefix = p[AUTO_BACKUP_S3_PREFIX] ?: defaults.autoBackup.s3.prefix,
                accessKeyId = p[AUTO_BACKUP_S3_KEY_ID] ?: defaults.autoBackup.s3.accessKeyId,
                secretAccessKey = p[AUTO_BACKUP_S3_SECRET]
                    ?: defaults.autoBackup.s3.secretAccessKey,
                pathStyle = p[AUTO_BACKUP_S3_PATH_STYLE] ?: defaults.autoBackup.s3.pathStyle,
            ),
            ftp = FtpConfig(
                host = p[AUTO_BACKUP_FTP_HOST] ?: defaults.autoBackup.ftp.host,
                port = p[AUTO_BACKUP_FTP_PORT] ?: defaults.autoBackup.ftp.port,
                user = p[AUTO_BACKUP_FTP_USER] ?: defaults.autoBackup.ftp.user,
                password = p[AUTO_BACKUP_FTP_PASSWORD] ?: defaults.autoBackup.ftp.password,
                path = p[AUTO_BACKUP_FTP_PATH] ?: defaults.autoBackup.ftp.path,
                secure = p[AUTO_BACKUP_FTP_SECURE] ?: defaults.autoBackup.ftp.secure,
            ),
            dropboxRefreshToken = p[AUTO_BACKUP_DROPBOX_TOKEN]
                ?: defaults.autoBackup.dropboxRefreshToken,
            oneDriveRefreshToken = p[AUTO_BACKUP_ONEDRIVE_TOKEN]
                ?: defaults.autoBackup.oneDriveRefreshToken,
            folderUri = p[AUTO_BACKUP_FOLDER_URI] ?: defaults.autoBackup.folderUri,
            intervalHours = p[AUTO_BACKUP_INTERVAL_HOURS]
                ?: defaults.autoBackup.intervalHours,
            keep = p[AUTO_BACKUP_KEEP] ?: defaults.autoBackup.keep,
            requireUnmetered = p[AUTO_BACKUP_UNMETERED]
                ?: defaults.autoBackup.requireUnmetered,
            requireCharging = p[AUTO_BACKUP_CHARGING]
                ?: defaults.autoBackup.requireCharging,
            // Absent means never chosen, so the defaults stand. An empty
            // set is a choice — every section turned off — and round-trips
            // as one, because the setter writes the key either way.
            sections = p[AUTO_BACKUP_SECTIONS] ?: defaults.autoBackup.sections,
            includeSecrets = p[AUTO_BACKUP_INCLUDE_SECRETS]
                ?: defaults.autoBackup.includeSecrets,
            encrypt = p[AUTO_BACKUP_ENCRYPT] ?: defaults.autoBackup.encrypt,
            passphrase = p[AUTO_BACKUP_PASSPHRASE] ?: defaults.autoBackup.passphrase,
            kdfSalt = p[AUTO_BACKUP_KDF_SALT] ?: defaults.autoBackup.kdfSalt,
            lastRunAtMs = p[AUTO_BACKUP_LAST_RUN_AT] ?: defaults.autoBackup.lastRunAtMs,
            lastError = p[AUTO_BACKUP_LAST_ERROR] ?: defaults.autoBackup.lastError,
            locationStatus = LocationStatus.decodeMap(p[AUTO_BACKUP_LOCATION_STATUS]),
            // Until an export list is chosen, the one the manual export
            // always used: the shared list it had before it got its own.
            exportSections = p[EXPORT_SECTIONS] ?: p[AUTO_BACKUP_SECTIONS]
                ?: defaults.autoBackup.exportSections,
            backupIncludeSecrets = p[AUTO_BACKUP_INCLUDE_KEYS] ?: defaults.autoBackup.backupIncludeSecrets,
            sync = SyncSettings(
                enabled = p[SYNC_ENABLED] ?: defaults.autoBackup.sync.enabled,
                mode = p[SYNC_MODE]?.let { id -> SyncMode.entries.firstOrNull { it.id == id } }
                    ?: defaults.autoBackup.sync.mode,
                intervalHours = p[SYNC_INTERVAL_HOURS] ?: defaults.autoBackup.sync.intervalHours,
                // Filled in below, once the locations are known.
                locationIds = defaults.autoBackup.sync.locationIds,
                sections = p[SYNC_SECTIONS] ?: defaults.autoBackup.sync.sections,
                includeSecrets = p[SYNC_INCLUDE_SECRETS] ?: defaults.autoBackup.sync.includeSecrets,
                keepLocal = p[SYNC_KEEP_LOCAL] ?: defaults.autoBackup.sync.keepLocal,
                lastRunAtMs = p[SYNC_LAST_RUN_AT] ?: defaults.autoBackup.sync.lastRunAtMs,
                lastError = p[SYNC_LAST_ERROR] ?: defaults.autoBackup.sync.lastError,
            ),
        ).let { auto ->
            // The list, once written, is the truth. Before that, the old
            // single destination is shown as the one location it was.
            val stored = p[AUTO_BACKUP_LOCATIONS]
            val locations = if (stored != null) {
                BackupLocation.decodeList(stored)
            } else {
                listOfNotNull(BackupLocation.fromLegacy(auto))
            }
            auto.copy(
                locations = locations,
                sync = auto.sync.copy(locationIds = syncLocationIds(p, locations)),
            )
        }

    private fun readSuggestionStrip(p: Preferences, defaults: KeyboardSettings) =
        SuggestionStripSettings(
            punctuation = p[PUNCTUATION_SUGGESTIONS] ?: defaults.suggestionStrip.punctuation,
            punctuationChips = p[PUNCTUATION_CHIPS]?.takeIf { it.isNotBlank() }
                ?: defaults.suggestionStrip.punctuationChips,
            slotCount = p[SUGGESTION_SLOT_COUNT] ?: defaults.suggestionStrip.slotCount,
            textScale = p[SUGGESTION_TEXT_SCALE] ?: defaults.suggestionStrip.textScale,
            scrollable = p[SUGGESTION_SCROLLABLE] ?: defaults.suggestionStrip.scrollable,
            primaryColor = p[SUGGESTION_PRIMARY_COLOR] ?: defaults.suggestionStrip.primaryColor,
            chipPadding = p[SUGGESTION_CHIP_PADDING] ?: defaults.suggestionStrip.chipPadding,
            learnedWordMinCount = p[LEARNED_WORD_MIN_COUNT]
                ?: defaults.suggestionStrip.learnedWordMinCount,
            newWordSightings = p[NEW_WORD_SIGHTINGS]
                ?: defaults.suggestionStrip.newWordSightings,
            askBeforeLearning = p[ASK_BEFORE_LEARNING]
                ?: defaults.suggestionStrip.askBeforeLearning,
            offerNearMissCorrections = p[OFFER_NEAR_MISS_CORRECTIONS]
                ?: defaults.suggestionStrip.offerNearMissCorrections,
            undoCorrectionChip = p[UNDO_CORRECTION_CHIP]
                ?: defaults.suggestionStrip.undoCorrectionChip,
            undoChipObviousness = p[UNDO_CHIP_OBVIOUSNESS]
                ?: defaults.suggestionStrip.undoChipObviousness,
            learnFromCorrections = p[LEARN_FROM_CORRECTIONS]
                ?: defaults.suggestionStrip.learnFromCorrections,
            adaptToTaps = p[ADAPT_TO_TAPS] ?: defaults.suggestionStrip.adaptToTaps,
            correctionsVersion = p[CORRECTIONS_VERSION]
                ?: defaults.suggestionStrip.correctionsVersion,
            suggestionsFirst = p[SUGGESTIONS_FIRST] ?: defaults.suggestionStrip.suggestionsFirst,
            suggestionPrimaryCenter = p[SUGGESTION_PRIMARY_CENTER]
                ?: defaults.suggestionStrip.suggestionPrimaryCenter,
            overflow = p[SUGGESTION_OVERFLOW]
                ?.let { runCatching { SuggestionOverflow.valueOf(it) }.getOrNull() }
                ?: defaults.suggestionStrip.overflow,
            blockOffensiveWords = p[BLOCK_OFFENSIVE_WORDS]
                ?: defaults.suggestionStrip.blockOffensiveWords,
            contextRerank = p[CONTEXT_RERANK]
                ?: defaults.suggestionStrip.contextRerank,
            autoSpaceAfterSuggestion = p[AUTO_SPACE_AFTER_SUGGESTION]
                ?: defaults.suggestionStrip.autoSpaceAfterSuggestion,
            skipTypedWord = p[SKIP_TYPED_WORD] ?: defaults.suggestionStrip.skipTypedWord,
            expandUserDictShortcuts = p[EXPAND_USER_DICT_SHORTCUTS]
                ?: defaults.suggestionStrip.expandUserDictShortcuts,
            useSystemDictionary = p[USE_SYSTEM_DICTIONARY]
                ?: defaults.suggestionStrip.useSystemDictionary,
            snippetMultiExpand = p[SNIPPET_MULTI_EXPAND]
                ?.let { runCatching { MultiExpandMode.valueOf(it) }.getOrNull() }
                ?: defaults.suggestionStrip.snippetMultiExpand,
            systemSmartReplies = p[SYSTEM_SMART_REPLIES]
                ?: defaults.suggestionStrip.systemSmartReplies,
            registerPriors = p[REGISTER_PRIORS]
                ?: defaults.suggestionStrip.registerPriors,
            timingSignalStrength = p[TIMING_SIGNAL_STRENGTH]
                ?: defaults.suggestionStrip.timingSignalStrength,
            numberRowCorrections = p[NUMBER_ROW_CORRECTIONS]
                ?: defaults.suggestionStrip.numberRowCorrections,
            numberPrediction = p[NUMBER_PREDICTION]
                ?: defaults.suggestionStrip.numberPrediction,
            autocorrectSplits = p[AUTOCORRECT_SPLITS]
                ?: defaults.suggestionStrip.autocorrectSplits,
            spellingMapOffLangs = p[SPELLING_MAP_OFF_LANGS]
                ?: defaults.suggestionStrip.spellingMapOffLangs,
            phoneticSiblingsOffLangs = p[PHONETIC_SIBLINGS_OFF_LANGS]
                ?: defaults.suggestionStrip.phoneticSiblingsOffLangs,
            importedOnlyLangs = p[IMPORTED_ONLY_LANGS]
                ?: defaults.suggestionStrip.importedOnlyLangs,
            wordPairsOffLangs = p[WORD_PAIRS_OFF_LANGS]
                ?: defaults.suggestionStrip.wordPairsOffLangs,
            languageDetection = p[LANGUAGE_DETECTION]
                ?: defaults.suggestionStrip.languageDetection,
            languageDetectionStrength = p[LANGUAGE_DETECTION_STRENGTH]
                ?.let { runCatching { LanguageDetectionStrength.valueOf(it) }.getOrNull() }
                ?: defaults.suggestionStrip.languageDetectionStrength,
            languageDetectionByApp = p[LANGUAGE_DETECTION_BY_APP]
                ?: defaults.suggestionStrip.languageDetectionByApp,
            phoneticEnglishLangs = p[PHONETIC_ENGLISH_LANGS]
                ?: LEGACY_PHONETIC_ENGLISH_LANGS.takeIf { p[PHONETIC_AUTO_ENGLISH] == true }
                ?: defaults.suggestionStrip.phoneticEnglishLangs,
            phoneticEnglishSwitch = p[PHONETIC_ENGLISH_SWITCH]
                ?: defaults.suggestionStrip.phoneticEnglishSwitch,
            phoneticFixedStripLangs = p[PHONETIC_FIXED_STRIP_LANGS]
                ?: defaults.suggestionStrip.phoneticFixedStripLangs,
            // A source name this build does not know is dropped, and the
            // language falls back to the default.
            phoneticStripSources = p[PHONETIC_STRIP_SOURCES]
                ?.mapNotNull { entry ->
                    val lang = entry.substringBefore('=', "")
                    val source = runCatching { PhoneticStripSource.valueOf(entry.substringAfter('=')) }
                        .getOrNull()
                    if (lang.isEmpty() || source == null) null else lang to source
                }
                ?.toMap()
                ?: defaults.suggestionStrip.phoneticStripSources,
            // An item name this build does not know is dropped, not kept
            // as a stale string.
            wordMenuItems = p[WORD_MENU_ITEMS]
                ?.let { stored ->
                    val items = stored.mapNotNullTo(mutableSetOf()) { runCatching { WordMenuItem.valueOf(it) }.getOrNull() }
                    if (WORD_MENU_SYNONYMS_MARK in stored) items else items + WordMenuItem.SYNONYMS
                }
                ?: defaults.suggestionStrip.wordMenuItems,
            synonymSources = p[SYNONYM_SOURCES]?.let(SynonymSources::decode)
                ?: defaults.suggestionStrip.synonymSources,
            rankControl = p[WORD_RANK_CONTROL]
                ?.let { runCatching { RankControl.valueOf(it) }.getOrNull() }
                ?: defaults.suggestionStrip.rankControl,
            deleteEditsImportedLists = p[DELETE_EDITS_IMPORTED_LISTS]
                ?: defaults.suggestionStrip.deleteEditsImportedLists,
            learnFromTextSort = p[LEARN_FROM_TEXT_SORT]
                ?.let { runCatching { LearnFromTextSort.valueOf(it) }.getOrNull() }
                ?: defaults.suggestionStrip.learnFromTextSort,
            learnFromTextPairs = p[LEARN_FROM_TEXT_PAIRS]
                ?: defaults.suggestionStrip.learnFromTextPairs,
        )

    private fun readKeyRepeat(p: Preferences, defaults: KeyboardSettings) =
        KeyRepeatSettings(
            deleteMs = p[KEY_REPEAT_DELETE] ?: p[KEY_REPEAT_INTERVAL]
                ?: defaults.keyRepeat.deleteMs,
            wordDeleteMs = p[KEY_REPEAT_WORD_DELETE] ?: defaults.keyRepeat.wordDeleteMs,
            spaceMs = p[KEY_REPEAT_SPACE] ?: p[KEY_REPEAT_INTERVAL]
                ?: defaults.keyRepeat.spaceMs,
            customKeyMs = p[KEY_REPEAT_CUSTOM] ?: defaults.keyRepeat.customKeyMs,
            startDelayMs = p[KEY_REPEAT_START_DELAY] ?: defaults.keyRepeat.startDelayMs,
        )

    private fun readOctopus(p: Preferences, defaults: KeyboardSettings) =
        OctopusSettings(
            enabled = p[OCTOPUS_ENABLED] ?: defaults.octopus.enabled,
            placement = p[OCTOPUS_PLACEMENT]
                ?.let { runCatching { OctopusPlacement.valueOf(it) }.getOrNull() }
                ?: defaults.octopus.placement,
            density = p[OCTOPUS_DENSITY] ?: defaults.octopus.density,
            kinds = p[OCTOPUS_KINDS]?.let(::decodeOctopusKinds) ?: defaults.octopus.kinds,
            duringGlide = p[OCTOPUS_DURING_GLIDE]
                ?.let { runCatching { OctopusDuringGlide.valueOf(it) }.getOrNull() }
                ?: defaults.octopus.duringGlide,
            flickCommits = p[OCTOPUS_FLICK_COMMITS] ?: defaults.octopus.flickCommits,
            tapCommits = p[OCTOPUS_TAP_COMMITS] ?: defaults.octopus.tapCommits,
            flickSensitivity = p[OCTOPUS_FLICK_SENSITIVITY]
                ?.let { runCatching { OctopusFlickSensitivity.valueOf(it) }.getOrNull() }
                ?: defaults.octopus.flickSensitivity,
            fontScale = p[OCTOPUS_FONT_SCALE] ?: defaults.octopus.fontScale,
            suppressHints = p[OCTOPUS_SUPPRESS_HINTS] ?: defaults.octopus.suppressHints,
            longPressKeys = p[OCTOPUS_LONG_PRESS_KEYS] ?: defaults.octopus.longPressKeys,
            wordsPerKey = p[OCTOPUS_WORDS_PER_KEY]
                ?.coerceIn(OctopusSettings.WORDS_PER_KEY_RANGE)
                ?: defaults.octopus.wordsPerKey,
        )

    private fun readLayoutBehavior(p: Preferences, defaults: KeyboardSettings) =
        LayoutBehaviorSettings(
            symbolsLongPressNumpad =
                p[SYMBOLS_LONGPRESS_NUMPAD] ?: defaults.layoutBehavior.symbolsLongPressNumpad,
            enterLongPressEmoji =
                p[ENTER_LONGPRESS_EMOJI] ?: defaults.layoutBehavior.enterLongPressEmoji,
            spaceSwipeDownHide =
                p[SPACE_SWIPE_DOWN_HIDE] ?: defaults.layoutBehavior.spaceSwipeDownHide,
            globeInOnePlace = p[GLOBE_IN_ONE_PLACE] ?: defaults.layoutBehavior.globeInOnePlace,
            hintFlick = p[HINT_FLICK] ?: defaults.layoutBehavior.hintFlick,
            capitalFlick = p[CAPITAL_FLICK] ?: defaults.layoutBehavior.capitalFlick,
            globeTypingGuardMs = p[GLOBE_TYPING_GUARD_MS]?.coerceIn(GlobeTypingGuardMsRange)
                ?: defaults.layoutBehavior.globeTypingGuardMs,
            boardCornerTopDp = p[BOARD_CORNER_TOP]?.coerceIn(BoardCornerRadiusRange)
                ?: defaults.layoutBehavior.boardCornerTopDp,
            boardCornerBottomDp = p[BOARD_CORNER_BOTTOM]?.coerceIn(BoardCornerRadiusRange)
                ?: defaults.layoutBehavior.boardCornerBottomDp,
            // Names this build does not know are dropped, not fatal.
            boardCorners = p[BOARD_CORNERS]
                ?.mapNotNullTo(mutableSetOf()) { name -> BoardCorner.entries.firstOrNull { it.name == name } }
                ?: defaults.layoutBehavior.boardCorners,
            spaceCursor2d = p[SPACE_CURSOR_2D] ?: defaults.layoutBehavior.spaceCursor2d,
            spaceHoldKeys = p[SPACE_HOLD_KEYS]
                ?.split('\n')?.filter { it.isNotEmpty() }
                ?: defaults.layoutBehavior.spaceHoldKeys,
            hintFontScale = p[HINT_FONT_SCALE] ?: defaults.layoutBehavior.hintFontScale,
            hintOffsetDp = p[HINT_OFFSET] ?: defaults.layoutBehavior.hintOffsetDp,
            transliterationHints = p[TRANSLITERATION_HINTS]
                ?.let { runCatching { TransliterationHintMode.valueOf(it) }.getOrNull() }
                ?: defaults.layoutBehavior.transliterationHints,
            fancyStyleId = p[FANCY_STYLE] ?: legacyFancyStyle(p)
                ?: defaults.layoutBehavior.fancyStyleId,
            // An empty string is how "no pinned style" is stored, so the
            // setting can go back to following the strip.
            fancyToolStyleId = p[FANCY_TOOL_STYLE]?.takeIf { it.isNotEmpty() }
                ?: defaults.layoutBehavior.fancyToolStyleId,
            fancyToolKeepsLanguage = p[FANCY_TOOL_KEEPS_LANGUAGE]
                ?: defaults.layoutBehavior.fancyToolKeepsLanguage,
            fancyToolAutoOff = p[FANCY_TOOL_AUTO_OFF]
                ?: defaults.layoutBehavior.fancyToolAutoOff,
            // Empty is "the first one", the same spelling the fancy style uses.
            customLayoutToolId = p[CUSTOM_LAYOUT_TOOL]?.takeIf { it.isNotEmpty() }
                ?: defaults.layoutBehavior.customLayoutToolId,
            numberRowShiftSymbols =
                p[NUMBER_ROW_SHIFT_SYMBOLS] ?: defaults.layoutBehavior.numberRowShiftSymbols,
            smartHitDetection =
                p[SMART_HIT_DETECTION] ?: defaults.layoutBehavior.smartHitDetection,
            autopilotStrength = p[AUTOPILOT_STRENGTH]
                ?: defaults.layoutBehavior.autopilotStrength,
            autopilotShowEffect = p[AUTOPILOT_SHOW_EFFECT]
                ?: defaults.layoutBehavior.autopilotShowEffect,
            autopilotOutline = p[AUTOPILOT_OUTLINE]
                ?: defaults.layoutBehavior.autopilotOutline,
            autopilotVisualScale = p[AUTOPILOT_VISUAL_SCALE]
                ?: defaults.layoutBehavior.autopilotVisualScale,
            spacebarDisplay = p[SPACEBAR_DISPLAY]
                ?.let { runCatching { SpacebarDisplay.valueOf(it) }.getOrNull() }
                ?: defaults.layoutBehavior.spacebarDisplay,
            languagePickerStyle = p[LANGUAGE_PICKER_STYLE]
                ?.let { runCatching { LanguagePickerStyle.valueOf(it) }.getOrNull() }
                ?: defaults.layoutBehavior.languagePickerStyle,
            spaceHoldPickerForLongRing = p[SPACE_HOLD_PICKER_FOR_LONG_RING]
                ?: defaults.layoutBehavior.spaceHoldPickerForLongRing,
            numeralSystemByLang = p[NUMERAL_SYSTEM_BY_LANG]
                ?.let { decodeNumeralSystems(it) }
                ?: defaults.layoutBehavior.numeralSystemByLang,
            numeralCommitScope = p[NUMERAL_COMMIT_SCOPE]
                ?.let { runCatching { NumeralCommitScope.valueOf(it) }.getOrNull() }
                ?: defaults.layoutBehavior.numeralCommitScope,
            shiftEnterNewline =
                p[SHIFT_ENTER_NEWLINE] ?: defaults.layoutBehavior.shiftEnterNewline,
            numberRowInSymbols =
                p[NUMBER_ROW_IN_SYMBOLS] ?: defaults.layoutBehavior.numberRowInSymbols,
            bottomRowHeightDp =
                p[BOTTOM_ROW_HEIGHT] ?: defaults.layoutBehavior.bottomRowHeightDp,
            sidePadLeftScale = p[SIDE_PAD_LEFT_SCALE] ?: p[SIDE_PAD_SCALE]
                ?: defaults.layoutBehavior.sidePadLeftScale,
            sidePadRightScale = p[SIDE_PAD_RIGHT_SCALE] ?: p[SIDE_PAD_SCALE]
                ?: defaults.layoutBehavior.sidePadRightScale,
            splitOnlyOnLargeScreens = p[SPLIT_ONLY_LARGE]
                ?: defaults.layoutBehavior.splitOnlyOnLargeScreens,
            shiftCapsLockMs = p[SHIFT_CAPS_LOCK_MS] ?: defaults.layoutBehavior.shiftCapsLockMs,
            showAllPopupKeys = p[SHOW_ALL_POPUP_KEYS] ?: defaults.layoutBehavior.showAllPopupKeys,
            shiftedPopupKeys = p[SHIFTED_POPUP_KEYS]
                ?: defaults.layoutBehavior.shiftedPopupKeys,
            currencyKeys = p[CURRENCY_KEYS]
                ?.split('\n')?.filter { it.isNotEmpty() }
                ?: defaults.layoutBehavior.currencyKeys,
            symbolsReturnToLetters = p[SYMBOLS_RETURN_TO_LETTERS]
                ?: defaults.layoutBehavior.symbolsReturnToLetters,
            symbolsReturnChars = p[SYMBOLS_RETURN_CHARS]
                ?: defaults.layoutBehavior.symbolsReturnChars,
            // Derived from whether the key is *there*, not from its value —
            // see the fields' own docs. This is the only place that
            // information survives; every other read collapses it with `?:`.
            numberRowUntouched = p[NUMBER_ROW] == null,
            keyHeightUntouched = p[KEY_HEIGHT] == null,
            keyboardWidthUntouched = p[KEYBOARD_WIDTH_PERCENT] == null,
        )

    private fun readLongPressLetterActions(p: Preferences, defaults: KeyboardSettings) =
        LongPressLetterActions(
            selectAll = p[LONG_PRESS_A_SELECT_ALL] ?: defaults.longPressLetterActions.selectAll,
            copy = p[LONG_PRESS_C_COPY] ?: defaults.longPressLetterActions.copy,
            paste = p[LONG_PRESS_V_PASTE] ?: defaults.longPressLetterActions.paste,
            cut = p[LONG_PRESS_X_CUT] ?: defaults.longPressLetterActions.cut,
            undo = p[LONG_PRESS_Z_UNDO] ?: defaults.longPressLetterActions.undo,
            redo = p[LONG_PRESS_Y_REDO] ?: defaults.longPressLetterActions.redo,
            letters = p[LONG_PRESS_LETTERS] ?: defaults.longPressLetterActions.letters,
            actionFirst = p[LONG_PRESS_ACTION_FIRST]
                ?: defaults.longPressLetterActions.actionFirst,
            globeDrag = p[GLOBE_DRAG_SHORTCUTS] ?: defaults.longPressLetterActions.globeDrag,
        )

    private fun readIcons(p: Preferences, defaults: KeyboardSettings) =
        IconSettings(
            activePackId = p[ICON_PACK_ID] ?: defaults.icons.activePackId,
            overrides = IconOverrides.decode(p[ICON_OVERRIDES]),
        )

    private fun readToolbarTools(p: Preferences, defaults: KeyboardSettings) =
        p[TOOLBAR_TOOLS]?.let { csv ->
            if (csv.isEmpty()) emptyList()
            else csv.split(',').mapNotNull { runCatching { ToolbarTool.valueOf(it) }.getOrNull() }
        } ?: defaults.toolbarTools

    private fun readToolbarBehavior(p: Preferences, defaults: KeyboardSettings) =
        ToolbarBehavior(
            enabled = p[TOOLBAR_ENABLED] ?: defaults.toolbarBehavior.enabled,
            swipeDownHide = p[TOOLBAR_SWIPE_DOWN_HIDE] ?: defaults.toolbarBehavior.swipeDownHide,
            dragToRearrange = p[TOOLBAR_DRAG_REARRANGE] ?: defaults.toolbarBehavior.dragToRearrange,
            onlyWithHardwareKeyboard =
                p[TOOLBAR_ONLY_HW_KEYBOARD] ?: defaults.toolbarBehavior.onlyWithHardwareKeyboard,
            reverseForRtl = p[REVERSE_TOOLBAR_RTL] ?: defaults.toolbarBehavior.reverseForRtl,
            greedy = p[TOOLBAR_GREEDY] ?: defaults.toolbarBehavior.greedy,
            scrollable = p[TOOLBAR_SCROLLABLE] ?: defaults.toolbarBehavior.scrollable,
            hideWhenLocked = p[TOOLBAR_HIDE_WHEN_LOCKED] ?: defaults.toolbarBehavior.hideWhenLocked,
            toolWidthDp = p[TOOLBAR_TOOL_WIDTH] ?: defaults.toolbarBehavior.toolWidthDp,
            paddingTopDp = p[TOOLBAR_PADDING_TOP] ?: defaults.toolbarBehavior.paddingTopDp,
            paddingBottomDp = p[TOOLBAR_PADDING_BOTTOM] ?: defaults.toolbarBehavior.paddingBottomDp,
            themesPanelBuiltIns = p[THEMES_PANEL_BUILTINS],
            placement = p[TOOLBAR_PLACEMENT]
                ?.let { runCatching { ToolbarPlacement.valueOf(it) }.getOrNull() }
                ?: defaults.toolbarBehavior.placement,
            showStrip = p[TOOLBAR_SHOW_STRIP] ?: defaults.toolbarBehavior.showStrip,
            holdActions = ToolHoldActions.decode(p[TOOLBAR_HOLD_ACTIONS]),
        )

    private fun readEmoji(p: Preferences, defaults: KeyboardSettings) =
        EmojiSettings(
            defaultSkinTone = p[EMOJI_DEFAULT_SKIN_TONE]
                ?.let { runCatching { EmojiSkinTone.valueOf(it) }.getOrNull() }
                ?: defaults.emoji.defaultSkinTone,
            toneOverrideByLastUsed = p[EMOJI_TONE_OVERRIDE_LAST_USED]
                ?: defaults.emoji.toneOverrideByLastUsed,
            closeAfterInsert = p[EMOJI_CLOSE_AFTER_INSERT] ?: defaults.emoji.closeAfterInsert,
            hideUnrenderable = p[EMOJI_HIDE_UNRENDERABLE] ?: defaults.emoji.hideUnrenderable,
            barScrollable = p[EMOJI_BAR_SCROLLABLE] ?: defaults.emoji.barScrollable,
            barCount = p[EMOJI_BAR_COUNT]?.coerceIn(EmojiBarCountRange)
                ?: defaults.emoji.barCount,
            gridCellSize = p[EMOJI_GRID_CELL_SIZE]?.coerceIn(EmojiGridCellSizeRange)
                ?: defaults.emoji.gridCellSize,
            gridEmojiSize = p[EMOJI_GRID_EMOJI_SIZE]?.coerceIn(EmojiGridEmojiSizeRange)
                ?: defaults.emoji.gridEmojiSize,
            recentsLimit = p[EMOJI_RECENTS_LIMIT]?.coerceIn(EmojiRecentsRange)
                ?: defaults.emoji.recentsLimit,
            mediaGridColumns = p[MEDIA_GRID_COLUMNS]?.coerceIn(2, 5)
                ?: defaults.emoji.mediaGridColumns,
            kaomojiTabs = p[EMOJI_KAOMOJI_TABS] ?: defaults.emoji.kaomojiTabs,
            keywordPackVersion = p[EMOJI_KEYWORD_PACK_VERSION]
                ?: defaults.emoji.keywordPackVersion,
            autoDownloadKeywords = p[EMOJI_AUTO_DOWNLOAD_KEYWORDS]
                ?: defaults.emoji.autoDownloadKeywords,
            disabledKeywordLangs = p[EMOJI_DISABLED_KEYWORD_LANGS]
                ?: defaults.emoji.disabledKeywordLangs,
            usageVersion = p[EMOJI_USAGE_VERSION] ?: defaults.emoji.usageVersion,
            animated = p[EMOJI_ANIMATED] ?: defaults.emoji.animated,
            sendAsSticker = p[EMOJI_SEND_AS_STICKER] ?: defaults.emoji.sendAsSticker,
            categoryOrder = p[EMOJI_CATEGORY_ORDER]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                ?: defaults.emoji.categoryOrder,
            hiddenCategories = p[EMOJI_HIDDEN_CATEGORIES] ?: defaults.emoji.hiddenCategories,
            categoryEmojiOrder = decodeEmojiOrder(p[EMOJI_CATEGORY_EMOJI_ORDER])
                .ifEmpty { defaults.emoji.categoryEmojiOrder },
        )

    private fun readToolbox(p: Preferences, defaults: KeyboardSettings) =
        ToolboxSettings(
            layout = p[TOOLBOX_LAYOUT]?.let { runCatching { ToolboxLayout.valueOf(it) }.getOrNull() }
                ?: defaults.toolbox.layout,
            pillColumns = p[TOOLBOX_PILL_COLUMNS]?.coerceIn(1, 3)
                ?: defaults.toolbox.pillColumns,
            pillFilled = p[TOOLBOX_PILL_FILLED] ?: defaults.toolbox.pillFilled,
            paginate = p[TOOLBOX_PAGINATE] ?: defaults.toolbox.paginate,
            pageSize = p[TOOLBOX_PAGE_SIZE]?.coerceIn(ToolboxPageSizeRange)
                ?: defaults.toolbox.pageSize,
            labelSizeSp = p[TOOLBOX_LABEL_SIZE] ?: defaults.toolbox.labelSizeSp,
            hiddenTools = decodeToolNames(p[TOOLBOX_HIDDEN_TOOLS]).toSet(),
        )

    private fun readSensorTools(p: Preferences, defaults: KeyboardSettings) =
        SensorToolSettings(
            flashlightAutoOff = p[FLASHLIGHT_AUTO_OFF]
                ?: defaults.sensorTools.flashlightAutoOff,
            compassDegrees = p[COMPASS_SHOW_DEGREES] ?: defaults.sensorTools.compassDegrees,
            compassQibla = p[COMPASS_SHOW_QIBLA] ?: defaults.sensorTools.compassQibla,
            levelAngles = p[LEVEL_SHOW_ANGLES] ?: defaults.sensorTools.levelAngles,
            moonSouthern = p[MOON_SOUTHERN] ?: isSouthernHemisphere(deviceRegion),
        )

    private fun readNetworkLog(p: Preferences, defaults: KeyboardSettings) =
        NetworkLogSettings(
            keep = p[NETWORK_LOG_KEEP] ?: defaults.networkLog.keep,
            showOnKeyboard = p[NETWORK_LOG_ON_KEYBOARD] ?: defaults.networkLog.showOnKeyboard,
        )

    private fun readWeather(p: Preferences, defaults: KeyboardSettings) =
        WeatherSettings(
            fahrenheit = p[WEATHER_FAHRENHEIT] ?: defaults.weather.fahrenheit,
            latitude = p[WEATHER_LAT],
            longitude = p[WEATHER_LON],
            placeName = p[WEATHER_PLACE] ?: defaults.weather.placeName,
            autoFetch = p[WEATHER_AUTO_FETCH] ?: defaults.weather.autoFetch,
        )

    private fun readCalendarTool(p: Preferences, defaults: KeyboardSettings) =
        CalendarToolSettings(
            altOne = calendarAltFromPrefs(p, first = true),
            altTwo = calendarAltFromPrefs(p, first = false),
            weekend = p[CALENDAR_WEEKEND]?.let { Weekend.fromId(it) }
                ?: Weekend.forRegion(deviceRegion),
            hijriAdjustDays = p[HIJRI_ADJUST_DAYS] ?: defaults.calendarTool.hijriAdjustDays,
        )

    private fun readVoiceBar(p: Preferences, defaults: KeyboardSettings) =
        VoiceBarSettings(
            mode = p[VOICE_UI_MODE] ?: if (p[VOICE_STRIP_MODE] == true) {
                VoiceBarSettings.MODE_STRIP
            } else {
                defaults.voiceBar.mode
            },
            typingMode = p[VOICE_TYPING_MODE] ?: defaults.voiceBar.typingMode,
            active = p[VOICE_BAR_ACTIVE] ?: defaults.voiceBar.active,
            vertical = p[VOICE_BAR_VERTICAL] ?: defaults.voiceBar.vertical,
            snap = p[VOICE_BAR_SNAP] ?: defaults.voiceBar.snap,
            rightEdge = p[VOICE_BAR_EDGE_RIGHT] ?: defaults.voiceBar.rightEdge,
            yBias = p[VOICE_BAR_Y_BIAS] ?: defaults.voiceBar.yBias,
            dockBias = p[VOICE_BAR_DOCK_BIAS] ?: defaults.voiceBar.dockBias,
            holdToTalkMs = p[VOICE_HOLD_TO_TALK_MS] ?: defaults.voiceBar.holdToTalkMs,
            holdPicksTypingMode = p[VOICE_HOLD_PICKS_MODE] ?: defaults.voiceBar.holdPicksTypingMode,
            returnMode = p[VOICE_UI_RETURN_MODE] ?: defaults.voiceBar.returnMode,
            inline = p[VOICE_BAR_INLINE] ?: defaults.voiceBar.inline,
        )

    private fun readWhisper(p: Preferences, defaults: KeyboardSettings) =
        WhisperSettings(
            engine = p[VOICE_ENGINE] ?: defaults.whisper.engine,
            modelId = p[WHISPER_MODEL_ID] ?: defaults.whisper.modelId,
            modelByLang = p[WHISPER_MODEL_BY_LANG]?.let { decodeWhisperModelByLang(it) }
                ?: defaults.whisper.modelByLang,
            translate = p[WHISPER_TRANSLATE] ?: defaults.whisper.translate,
            serverUrl = p[VOICE_SERVER_URL] ?: defaults.whisper.serverUrl,
            serverKey = p[VOICE_SERVER_KEY] ?: defaults.whisper.serverKey,
            serverModel = p[VOICE_SERVER_MODEL] ?: defaults.whisper.serverModel,
            serverSendLanguage = p[VOICE_SERVER_SEND_LANGUAGE]
                ?: defaults.whisper.serverSendLanguage,
            biasPersonalWords = p[VOICE_BIAS_PERSONAL_WORDS] ?: defaults.whisper.biasPersonalWords,
            biasWords = p[VOICE_BIAS_WORDS] ?: defaults.whisper.biasWords,
            serverPrompt = p[VOICE_SERVER_PROMPT] ?: defaults.whisper.serverPrompt,
        )

    private fun readCamera(p: Preferences, defaults: KeyboardSettings) =
        CameraSettings(
            preferFront = p[CAMERA_PREFER_FRONT] ?: defaults.camera.preferFront,
            timerSeconds = p[CAMERA_TIMER_SECONDS] ?: defaults.camera.timerSeconds,
            captureMaxPx = p[CAMERA_CAPTURE_MAX_PX] ?: defaults.camera.captureMaxPx,
            mirrorFront = p[CAMERA_MIRROR_FRONT] ?: defaults.camera.mirrorFront,
            shutterSound = p[CAMERA_SHUTTER_SOUND] ?: defaults.camera.shutterSound,
            haptics = p[CAMERA_HAPTICS] ?: defaults.camera.haptics,
            saveToGallery = p[CAMERA_SAVE_TO_GALLERY] ?: defaults.camera.saveToGallery,
            fullFrame = p[CAMERA_FULL_FRAME] ?: defaults.camera.fullFrame,
            searchButton = p[CAMERA_SEARCH_BUTTON] ?: defaults.camera.searchButton,
            searchWith = p[CAMERA_SEARCH_WITH]
                ?.let { runCatching { PhotoSearchTarget.valueOf(it) }.getOrNull() }
                ?: defaults.camera.searchWith,
            searchEngine = p[CAMERA_SEARCH_ENGINE]
                ?.let { runCatching { PhotoSearchEngine.valueOf(it) }.getOrNull() }
                ?: defaults.camera.searchEngine,
            searchCustomUrl = p[CAMERA_SEARCH_CUSTOM_URL] ?: defaults.camera.searchCustomUrl,
            searchCustomField = p[CAMERA_SEARCH_CUSTOM_FIELD]
                ?.takeIf { it.isNotBlank() } ?: defaults.camera.searchCustomField,
        )

    private fun readScanner(p: Preferences, defaults: KeyboardSettings) =
        ScannerSettings(
            docSaveToGallery = p[DOC_SCAN_SAVE_TO_GALLERY]
                ?: defaults.scanner.docSaveToGallery,
            qrSaveToGallery = p[QR_SAVE_TO_GALLERY] ?: defaults.scanner.qrSaveToGallery,
            qrSendMode = p[QR_SEND_MODE]
                ?.let { runCatching { MediaSendMode.valueOf(it) }.getOrNull() }
                ?: defaults.scanner.qrSendMode,
            ocrAutoSelectWords = p[OCR_AUTO_SELECT_WORDS]
                ?: defaults.scanner.ocrAutoSelectWords,
            ocrEngine = p[OCR_ENGINE]?.let { runCatching { OcrEngine.valueOf(it) }.getOrNull() }
                ?: defaults.scanner.ocrEngine,
            qrScanHaptics = p[QR_SCAN_HAPTICS] ?: defaults.scanner.qrScanHaptics,
            qrScanAutoInsert = p[QR_SCAN_AUTO_INSERT] ?: defaults.scanner.qrScanAutoInsert,
            qrScanLinkPreviews = p[QR_SCAN_LINK_PREVIEWS]
                ?: defaults.scanner.qrScanLinkPreviews,
            qrSizePx = p[QR_SIZE_PX] ?: defaults.scanner.qrSizePx,
            qrEcc = p[QR_ECC]?.let { runCatching { QrEccLevel.valueOf(it) }.getOrNull() }
                ?: defaults.scanner.qrEcc,
        )

    private fun readGif(p: Preferences, defaults: KeyboardSettings) =
        GifSettings(
            klipyApiKey = p[KLIPY_API_KEY] ?: defaults.gif.klipyApiKey,
            giphyApiKey = p[GIPHY_API_KEY] ?: defaults.gif.giphyApiKey,
            contentFilter = p[GIF_CONTENT_FILTER]
                ?.let { runCatching { GifContentFilter.valueOf(it) }.getOrNull() }
                ?: defaults.gif.contentFilter,
            sourceMode = p[GIF_SOURCE_MODE]
                ?.let { runCatching { GifSourceMode.valueOf(it) }.getOrNull() }
                ?: defaults.gif.sourceMode,
            resultLimit = p[GIF_RESULT_LIMIT] ?: defaults.gif.resultLimit,
            sendMode = p[GIF_SEND_MODE]
                ?.let { runCatching { MediaSendMode.valueOf(it) }.getOrNull() }
                ?: defaults.gif.sendMode,
            stickerSuggest = p[STICKER_SUGGEST] ?: defaults.gif.stickerSuggest,
            stickerSuggestStyle = p[STICKER_SUGGEST_STYLE]
                ?.let { runCatching { StickerSuggestStyle.valueOf(it) }.getOrNull() }
                ?: defaults.gif.stickerSuggestStyle,
            stickerSuggestTrigger = p[STICKER_SUGGEST_TRIGGER]
                ?.let { runCatching { StickerTriggerAction.valueOf(it) }.getOrNull() }
                ?: defaults.gif.stickerSuggestTrigger,
        )

    private fun readTextEditing(p: Preferences, defaults: KeyboardSettings) =
        TextEditingSettings(
            repeatMs = p[TEXT_EDIT_REPEAT_MS] ?: defaults.textEditing.repeatMs,
            cursorToolsRepeatOnHold = p[CURSOR_TOOLS_REPEAT_ON_HOLD]
                ?: defaults.textEditing.cursorToolsRepeatOnHold,
            // Filtered rather than trusted: the stored list outlives a tool
            // leaving HoldRepeatCursorTools, and a name in here that no
            // longer repeats would quietly cost that tool its toolbox hold.
            toolboxRepeatTools = p[TOOLBOX_REPEAT_TOOLS]
                ?.let { csv -> decodeToolNames(csv).filterTo(HashSet()) { it in HoldRepeatCursorTools } }
                ?: defaults.textEditing.toolboxRepeatTools,
            selectionModeHold = p[SELECTION_MODE_HOLD]
                ?: defaults.textEditing.selectionModeHold,
            selectionModeMultiTap = p[SELECTION_MODE_MULTI_TAP]
                ?: defaults.textEditing.selectionModeMultiTap,
            wrapSelectionWithPair =
                p[WRAP_SELECTION_WITH_PAIR] ?: defaults.textEditing.wrapSelectionWithPair,
            autoCloseBrackets =
                p[AUTO_CLOSE_BRACKETS] ?: defaults.textEditing.autoCloseBrackets,
            recapitalizeSelectionWithShift = p[RECAPITALIZE_SELECTION_WITH_SHIFT]
                ?: defaults.textEditing.recapitalizeSelectionWithShift,
            doubleSpaceWindowMs = p[DOUBLE_SPACE_WINDOW_MS]
                ?: defaults.textEditing.doubleSpaceWindowMs,
            spaceCursorStepDp = p[SPACE_CURSOR_STEP_DP]
                ?: defaults.textEditing.spaceCursorStepDp,
            spaceCursorMagnifier = p[SPACE_CURSOR_MAGNIFIER]
                ?: defaults.textEditing.spaceCursorMagnifier,
            backspaceWordStepDp = p[BACKSPACE_WORD_STEP_DP]
                ?: defaults.textEditing.backspaceWordStepDp,
            backspaceSwipeUnit = p[BACKSPACE_SWIPE_UNIT]
                ?.let { runCatching { BackspaceSwipeUnit.valueOf(it) }.getOrNull() }
                ?: defaults.textEditing.backspaceSwipeUnit,
            backspaceSwipePreview = p[BACKSPACE_SWIPE_PREVIEW]
                ?: defaults.textEditing.backspaceSwipePreview,
            backspaceCharStepDp = p[BACKSPACE_CHAR_STEP_DP]
                ?: defaults.textEditing.backspaceCharStepDp,
            deleteHoldDeletesWords = p[DELETE_HOLD_DELETES_WORDS]
                ?: defaults.textEditing.deleteHoldDeletesWords,
            forwardDeleteSwipe = p[FORWARD_DELETE_SWIPE]
                ?: defaults.textEditing.forwardDeleteSwipe,
        )

    private fun readTrackpad(p: Preferences, defaults: KeyboardSettings) =
        TrackpadSettings(
            stepXDp = p[TRACKPAD_STEP_X_DP] ?: defaults.trackpad.stepXDp,
            stepYDp = p[TRACKPAD_STEP_Y_DP] ?: defaults.trackpad.stepYDp,
            holdToOpen = p[TRACKPAD_HOLD_TO_OPEN] ?: defaults.trackpad.holdToOpen,
            multiTap = p[TRACKPAD_MULTI_TAP] ?: defaults.trackpad.multiTap,
            haptics = p[TRACKPAD_HAPTICS] ?: defaults.trackpad.haptics,
            trail = p[TRACKPAD_TRAIL] ?: defaults.trackpad.trail,
            magnifier = p[TRACKPAD_MAGNIFIER] ?: defaults.trackpad.magnifier,
        )

    private fun readVocabulary(p: Preferences, defaults: KeyboardSettings) =
        VocabularySettings(
            nudges = p[VOCAB_NUDGES] ?: defaults.vocabulary.nudges,
            nudgeOnVocabWord = p[VOCAB_NUDGE_SELF] ?: defaults.vocabulary.nudgeOnVocabWord,
            nudgeScope = p[VOCAB_NUDGE_SCOPE]?.let { runCatching { VocabNudgeScope.valueOf(it) }.getOrNull() }
                ?: defaults.vocabulary.nudgeScope,
            nudgeLevel = p[VOCAB_NUDGE_LEVEL]?.let { runCatching { VocabNudgeLevel.valueOf(it) }.getOrNull() }
                ?: defaults.vocabulary.nudgeLevel,
            cooldown = p[VOCAB_COOLDOWN]?.let { runCatching { VocabCooldown.valueOf(it) }.getOrNull() }
                ?: defaults.vocabulary.cooldown,
            chipTapAction = p[VOCAB_CHIP_TAP]?.let { runCatching { VocabChipTap.valueOf(it) }.getOrNull() }
                ?: defaults.vocabulary.chipTapAction,
            relatedTap = p[VOCAB_RELATED_TAP]?.let { runCatching { VocabRelatedTap.valueOf(it) }.getOrNull() }
                ?: defaults.vocabulary.relatedTap,
            scheduler = p[VOCAB_SCHEDULER]?.let { runCatching { VocabScheduler.valueOf(it) }.getOrNull() }
                ?: defaults.vocabulary.scheduler,
            dailyGoal = p[VOCAB_DAILY_GOAL] ?: defaults.vocabulary.dailyGoal,
            wordOfTheDayCard = p[VOCAB_WOTD_CARD] ?: defaults.vocabulary.wordOfTheDayCard,
            wordOfTheDayChip = p[VOCAB_WOTD_CHIP] ?: defaults.vocabulary.wordOfTheDayChip,
            wordInterval = p[VOCAB_WORD_INTERVAL]?.let { runCatching { VocabWordInterval.valueOf(it) }.getOrNull() }
                ?: defaults.vocabulary.wordInterval,
            chipTimesPerWord = p[VOCAB_CHIP_TIMES]?.coerceIn(VocabularySettings.MIN_CHIP_TIMES, VocabularySettings.MAX_CHIP_TIMES)
                ?: defaults.vocabulary.chipTimesPerWord,
            audioSource = p[VOCAB_AUDIO_SOURCE]?.let { runCatching { VocabAudioSource.valueOf(it) }.getOrNull() }
                ?: defaults.vocabulary.audioSource,
            accent = p[VOCAB_ACCENT]?.let { runCatching { VocabAccent.valueOf(it) }.getOrNull() }
                ?: defaults.vocabulary.accent,
            ttsRate = p[VOCAB_TTS_RATE] ?: defaults.vocabulary.ttsRate,
            ttsPitch = p[VOCAB_TTS_PITCH] ?: defaults.vocabulary.ttsPitch,
            cardFields = p[VOCAB_CARD_FIELDS] ?: defaults.vocabulary.cardFields,
            translationLangs = p[VOCAB_TRANSLATION_LANGS] ?: defaults.vocabulary.translationLangs,
        )

    private fun readPowerSaving(p: Preferences, defaults: KeyboardSettings) =
        PowerSavingSettings(
            manual = p[PS_MANUAL] ?: defaults.powerSaving.manual,
            trigger = p[PS_TRIGGER]
                ?.let { runCatching { PowerSavingTrigger.valueOf(it) }.getOrNull() }
                ?: defaults.powerSaving.trigger,
            batteryPercent = p[PS_BATTERY_PERCENT] ?: defaults.powerSaving.batteryPercent,
            offWhileCharging =
                p[PS_OFF_WHILE_CHARGING] ?: defaults.powerSaving.offWhileCharging,
            dropHaptics = p[PS_DROP_HAPTICS] ?: defaults.powerSaving.dropHaptics,
            dropKeySound = p[PS_DROP_KEY_SOUND] ?: defaults.powerSaving.dropKeySound,
            dropAnimations = p[PS_DROP_ANIMATIONS] ?: defaults.powerSaving.dropAnimations,
            dropGlideTrail = p[PS_DROP_GLIDE_TRAIL] ?: defaults.powerSaving.dropGlideTrail,
            dropKeyPopup = p[PS_DROP_KEY_POPUP] ?: defaults.powerSaving.dropKeyPopup,
            dropGestureTyping =
                p[PS_DROP_GESTURE_TYPING] ?: defaults.powerSaving.dropGestureTyping,
            dropEmojiPrediction =
                p[PS_DROP_EMOJI_PREDICTION] ?: defaults.powerSaving.dropEmojiPrediction,
            dropSmartChips = p[PS_DROP_SMART_CHIPS] ?: defaults.powerSaving.dropSmartChips,
            dropBackgroundNetwork =
                p[PS_DROP_BACKGROUND_NETWORK] ?: defaults.powerSaving.dropBackgroundNetwork,
            dropScreenshotWatch =
                p[PS_DROP_SCREENSHOT_WATCH] ?: defaults.powerSaving.dropScreenshotWatch,
            dropOnDeviceModels =
                p[PS_DROP_ON_DEVICE_MODELS] ?: defaults.powerSaving.dropOnDeviceModels,
            dropTypingStats =
                p[PS_DROP_TYPING_STATS] ?: defaults.powerSaving.dropTypingStats,
            dropMediaPin =
                p[PS_DROP_MEDIA_PIN] ?: defaults.powerSaving.dropMediaPin,
        )

    private fun readRateSources(p: Preferences, defaults: KeyboardSettings) =
        RateSourceSettings(
            fiatProviders = p[FIAT_PROVIDERS]?.split('\n')?.filter { it.isNotEmpty() }
                ?: defaults.rateSources.fiatProviders,
            cryptoEnabled = p[CRYPTO_ENABLED] ?: defaults.rateSources.cryptoEnabled,
            cryptoProviders = p[CRYPTO_PROVIDERS]?.split('\n')?.filter { it.isNotEmpty() }
                ?: defaults.rateSources.cryptoProviders,
            cryptoCacheMinutes = p[CRYPTO_CACHE_MINUTES]
                ?: defaults.rateSources.cryptoCacheMinutes,
            cryptoTickers = p[CRYPTO_TICKERS] ?: defaults.rateSources.cryptoTickers,
            cryptoDecimals = p[CRYPTO_DECIMALS] ?: defaults.rateSources.cryptoDecimals,
            autoFetch = p[CURRENCY_AUTO_FETCH] ?: defaults.rateSources.autoFetch,
        )

    private fun readTranslate(p: Preferences, defaults: KeyboardSettings) =
        TranslateSettings(
            engine = p[TRANSLATE_ENGINE]
                ?.let { name -> TranslateEngine.entries.firstOrNull { it.name == name } }
                ?: defaults.translate.engine,
            downloadedFirst = p[TRANSLATE_DOWNLOADED_FIRST] ?: defaults.translate.downloadedFirst,
            onlyDownloaded = p[TRANSLATE_ONLY_DOWNLOADED] ?: defaults.translate.onlyDownloaded,
            deepl = DeepLSettings(
                apiKey = p[DEEPL_API_KEY] ?: defaults.translate.deepl.apiKey,
                endpoint = p[DEEPL_ENDPOINT] ?: defaults.translate.deepl.endpoint,
                translate = p[DEEPL_TRANSLATE] ?: defaults.translate.deepl.translate,
                write = p[DEEPL_WRITE] ?: defaults.translate.deepl.write,
                writeStyle = p[DEEPL_WRITE_STYLE]
                    ?.let { name -> DeepLWriteStyle.entries.firstOrNull { it.name == name } }
                    ?: defaults.translate.deepl.writeStyle,
            ),
        )

    private fun readGrammarHiddenKinds(p: Preferences, defaults: KeyboardSettings) =
        p[GRAMMAR_HIDDEN_KINDS]
            ?.mapNotNullTo(mutableSetOf()) {
                runCatching { GrammarLintKind.valueOf(it) }.getOrNull()
            }
            ?: defaults.grammarHiddenKinds

    private fun readWebSearch(p: Preferences, defaults: KeyboardSettings) =
        WebSearchSettings(
            braveApiKey = p[BRAVE_API_KEY] ?: defaults.webSearch.braveApiKey,
            safe = p[SEARCH_SAFE] ?: defaults.webSearch.safe,
            resultCount = p[SEARCH_RESULT_COUNT] ?: defaults.webSearch.resultCount,
            wikiLanguage = p[WIKI_LANGUAGE] ?: defaults.webSearch.wikiLanguage,
            wikiLinksMarkdown = p[WIKI_LINKS_MARKDOWN]
                ?: defaults.webSearch.wikiLinksMarkdown,
        )

    private fun readBarOrder(p: Preferences, defaults: KeyboardSettings) =
        p[BAR_ORDER]
            ?.split(',')
            ?.mapNotNull { runCatching { BarRow.valueOf(it) }.getOrNull() }
            ?.let { sanitizeBarOrder(it) }
            ?: if (p[EMOJI_ROW_ABOVE_TOOLBAR] == false) {
                // Legacy toggle explicitly off = emoji row below the toolbar.
                // true (emoji above) and unset both fall through to the
                // default order, which already puts the emoji row first.
                sanitizeBarOrder(listOf(BarRow.TOPBAR, BarRow.EMOJI, BarRow.SYMBOL))
            } else {
                defaults.barOrder
            }

    private fun readSmartChips(p: Preferences, defaults: KeyboardSettings) =
        SmartChipSettings(
            dates = p[SMART_CHIP_DATES] ?: defaults.smartChips.dates,
            weather = p[SMART_CHIP_WEATHER] ?: defaults.smartChips.weather,
            lookups = p[SMART_CHIP_LOOKUPS] ?: defaults.smartChips.lookups,
            intents = p[SMART_CHIP_INTENTS] ?: defaults.smartChips.intents,
            gifs = p[SMART_CHIP_GIFS] ?: defaults.smartChips.gifs,
            numbers = p[SMART_CHIP_NUMBERS] ?: defaults.smartChips.numbers,
            numberGrouping = p[SMART_CHIP_NUMBER_GROUPING]
                ?.let { runCatching { NumberGrouping.valueOf(it) }.getOrNull() }
                ?: defaults.smartChips.numberGrouping,
        )

    private fun readSelectionMacros(p: Preferences, defaults: KeyboardSettings) =
        SelectionMacroSettings(
            enabled = p[SELECTION_MACROS_ENABLED] ?: defaults.selectionMacros.enabled,
            placement = p[SELECTION_MACROS_PLACEMENT]
                ?.let { name -> runCatching { SelectionMacroPlacement.valueOf(name) }.getOrNull() }
                ?: defaults.selectionMacros.placement,
            macros = SelectionMacroCodec.decodeMacros(p[SELECTION_MACROS_LIST_VERSION], p[SELECTION_MACROS_ON]),
            order = SelectionMacroCodec.decodeOrder(p[SELECTION_MACROS_LIST_VERSION], p[SELECTION_MACROS_ORDER]),
            aiDirectActions = p[SELECTION_MACROS_AI_ACTIONS]?.let(AiActionCodec::decodeIds)
                ?: defaults.selectionMacros.aiDirectActions,
            timeZones = p[SELECTION_MACROS_TIME_ZONES]?.let(AiActionCodec::decodeIds)
                ?: defaults.selectionMacros.timeZones,
            detectEntities = p[SELECTION_MACROS_DETECT] ?: defaults.selectionMacros.detectEntities,
        )

    private fun readPasswordGenerator(p: Preferences, defaults: KeyboardSettings) =
        PasswordGeneratorSettings(
            pwLength = p[PW_LENGTH] ?: defaults.passwordGenerator.pwLength,
            pwUppercase = p[PW_UPPERCASE] ?: defaults.passwordGenerator.pwUppercase,
            pwDigits = p[PW_DIGITS] ?: defaults.passwordGenerator.pwDigits,
            pwSymbols = p[PW_SYMBOLS] ?: defaults.passwordGenerator.pwSymbols,
            pwExcludeAmbiguous = p[PW_EXCLUDE_AMBIGUOUS]
                ?: defaults.passwordGenerator.pwExcludeAmbiguous,
            pwPassphraseMode = p[PW_PASSPHRASE_MODE]
                ?: defaults.passwordGenerator.pwPassphraseMode,
            ppWordCount = p[PP_WORD_COUNT] ?: defaults.passwordGenerator.ppWordCount,
            ppSeparator = p[PP_SEPARATOR] ?: defaults.passwordGenerator.ppSeparator,
            ppCapitalize = p[PP_CAPITALIZE] ?: defaults.passwordGenerator.ppCapitalize,
            ppIncludeDigit = p[PP_INCLUDE_DIGIT] ?: defaults.passwordGenerator.ppIncludeDigit,
        )

    private fun readTypingTest(p: Preferences, defaults: KeyboardSettings) =
        TypingTestSettings(
            mode = p[TT_MODE]?.let { runCatching { TypingTestMode.valueOf(it) }.getOrNull() }
                ?: defaults.typingTest.mode,
            duration = p[TT_DURATION] ?: defaults.typingTest.duration,
            wordCount = p[TT_WORD_COUNT] ?: defaults.typingTest.wordCount,
            punctuation = p[TT_PUNCTUATION] ?: defaults.typingTest.punctuation,
            numbers = p[TT_NUMBERS] ?: defaults.typingTest.numbers,
            glide = p[TT_GLIDE] ?: defaults.typingTest.glide,
            suggestions = p[TT_SUGGESTIONS] ?: defaults.typingTest.suggestions,
            bests = p[TT_BESTS] ?: defaults.typingTest.bests,
            history = p[TT_HISTORY] ?: defaults.typingTest.history,
            completed = p[TT_COMPLETED] ?: defaults.typingTest.completed,
            achievements = p[TT_ACHIEVEMENTS] ?: defaults.typingTest.achievements,
        )

    private fun readAi(p: Preferences, defaults: KeyboardSettings) =
        AiSettings(
            provider = p[AI_PROVIDER]
                ?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() }
                // On-device models exist only where the engine does. A value
                // restored from a full-build backup would otherwise open the
                // model downloader on lite, which reaches Hugging Face for a
                // model nothing there can run.
                ?.takeUnless { it == AiProvider.ON_DEVICE && !BuildConfig.ENABLE_LOCAL_LLM }
                ?: defaults.ai.provider,
            anthropicKey = p[AI_ANTHROPIC_KEY] ?: defaults.ai.anthropicKey,
            openAiKey = p[AI_OPENAI_KEY] ?: defaults.ai.openAiKey,
            geminiKey = p[AI_GEMINI_KEY] ?: defaults.ai.geminiKey,
            anthropicModel = p[AI_ANTHROPIC_MODEL] ?: defaults.ai.anthropicModel,
            openAiModel = p[AI_OPENAI_MODEL] ?: defaults.ai.openAiModel,
            geminiModel = p[AI_GEMINI_MODEL] ?: defaults.ai.geminiModel,
            ollamaUrl = p[AI_OLLAMA_URL] ?: defaults.ai.ollamaUrl,
            ollamaModel = p[AI_OLLAMA_MODEL] ?: defaults.ai.ollamaModel,
            lmStudioUrl = p[AI_LM_STUDIO_URL] ?: defaults.ai.lmStudioUrl,
            lmStudioModel = p[AI_LM_STUDIO_MODEL] ?: defaults.ai.lmStudioModel,
            xaiKey = p[AI_XAI_KEY] ?: defaults.ai.xaiKey,
            xaiModel = p[AI_XAI_MODEL] ?: defaults.ai.xaiModel,
            deepSeekKey = p[AI_DEEPSEEK_KEY] ?: defaults.ai.deepSeekKey,
            deepSeekModel = p[AI_DEEPSEEK_MODEL] ?: defaults.ai.deepSeekModel,
            compatibleUrl = p[AI_COMPATIBLE_URL] ?: defaults.ai.compatibleUrl,
            compatibleKey = p[AI_COMPATIBLE_KEY] ?: defaults.ai.compatibleKey,
            compatibleModel = p[AI_COMPATIBLE_MODEL] ?: defaults.ai.compatibleModel,
            maxTokens = p[AI_MAX_TOKENS] ?: defaults.ai.maxTokens,
            localContextTokens = p[AI_LOCAL_CONTEXT_TOKENS] ?: defaults.ai.localContextTokens,
            translateTo = p[AI_TRANSLATE_TO] ?: defaults.ai.translateTo,
            // Folded in on every read rather than behind a "migrated" flag:
            // a restored backup puts the old keys back, and a flag would
            // make that restored prompt invisible for good.
            customActions = mergeLegacyAiPrompts(
                custom = AiActionCodec.decodeList(p[AI_CUSTOM_ACTIONS].orEmpty()),
                legacy = legacyAiPrompts(p),
            ),
            actionOrder = AiActionCodec.decodeIds(p[AI_ACTION_ORDER].orEmpty()),
            hiddenActions = AiActionCodec.decodeIds(p[AI_ACTIONS_OFF].orEmpty()),
            localModelId = p[AI_LOCAL_MODEL_ID] ?: defaults.ai.localModelId,
            localBackend = p[AI_LOCAL_BACKEND]
                ?.let { runCatching { LocalLlmBackend.valueOf(it) }.getOrNull() }
                ?: defaults.ai.localBackend,
            hfToken = p[HF_TOKEN] ?: defaults.ai.hfToken,
            showThinking = p[AI_SHOW_THINKING] ?: defaults.ai.showThinking,
            panelModelPicker = p[AI_PANEL_MODEL_PICKER] ?: defaults.ai.panelModelPicker,
            diffView = p[AI_DIFF_VIEW] ?: defaults.ai.diffView,
            diffOpensFirst = p[AI_DIFF_OPENS_FIRST] ?: defaults.ai.diffOpensFirst,
            historyEnabled = p[AI_HISTORY_ENABLED] ?: defaults.ai.historyEnabled,
            historyMax = p[AI_HISTORY_MAX] ?: defaults.ai.historyMax,
            keepChats = p[AI_KEEP_CHATS] ?: defaults.ai.keepChats,
            chatEnterSends = p[AI_CHAT_ENTER_SENDS] ?: defaults.ai.chatEnterSends,
            panelChat = p[AI_PANEL_CHAT] ?: defaults.ai.panelChat,
            beforeCursorChars = p[AI_BEFORE_CURSOR_CHARS]
                ?: defaults.ai.beforeCursorChars,
        )

    private fun readLauncher(p: Preferences, defaults: KeyboardSettings) =
        LauncherToolSettings(
            sortOrder = p[LAUNCHER_SORT]
                ?.let { runCatching { AppSortOrder.valueOf(it) }.getOrNull() }
                ?: defaults.launcher.sortOrder,
            showLabels = p[LAUNCHER_SHOW_LABELS] ?: defaults.launcher.showLabels,
            recentsEnabled = p[LAUNCHER_RECENTS_ENABLED] ?: defaults.launcher.recentsEnabled,
            maxRecents = p[LAUNCHER_MAX_RECENTS] ?: defaults.launcher.maxRecents,
            activityDrilldown = p[LAUNCHER_DRILLDOWN] ?: defaults.launcher.activityDrilldown,
            showNonExported =
                p[LAUNCHER_SHOW_NON_EXPORTED] ?: defaults.launcher.showNonExported,
            pinned = p[LAUNCHER_PINNED]?.split('\t')?.filter { it.isNotEmpty() }.orEmpty(),
            // Trimmed on read as well as on write: a cap lowered while the
            // stored list was longer takes effect immediately rather than
            // on the next launch that happens to rewrite the list.
            recents = p[LAUNCHER_RECENTS]?.split('\t')?.filter { it.isNotEmpty() }.orEmpty()
                .take(p[LAUNCHER_MAX_RECENTS] ?: defaults.launcher.maxRecents),
            gridColumns = p[LAUNCHER_GRID_COLUMNS] ?: defaults.launcher.gridColumns,
            iconSizeDp = p[LAUNCHER_ICON_SIZE] ?: defaults.launcher.iconSizeDp,
            iconShape = p[LAUNCHER_ICON_SHAPE]
                ?.let { runCatching { LauncherIconShape.valueOf(it) }.getOrNull() }
                ?: defaults.launcher.iconShape,
            hidden = p[LAUNCHER_HIDDEN]?.split('\t')?.filter { it.isNotEmpty() }.orEmpty(),
            openMode = p[LAUNCHER_OPEN_MODE]
                ?.let { runCatching { LauncherOpenMode.valueOf(it) }.getOrNull() }
                ?: defaults.launcher.openMode,
            combos = LauncherSplitCombo.decode(p[LAUNCHER_COMBOS]),
        )

    private fun readMediaControl(p: Preferences, defaults: KeyboardSettings) =
        MediaControlSettings(
            pinWhilePlaying = p[MEDIA_PIN_WHILE_PLAYING]
                ?: defaults.mediaControl.pinWhilePlaying,
            musicApps = p[MEDIA_MUSIC_APPS] ?: defaults.mediaControl.musicApps,
        )

    private fun readKdeConnect(p: Preferences, defaults: KeyboardSettings) =
        KdeConnectSettings(
            enabled = p[KDE_ENABLED] ?: defaults.kdeConnect.enabled,
            deviceName = p[KDE_DEVICE_NAME] ?: defaults.kdeConnect.deviceName,
            lifetime = p[KDE_LIFETIME]
                ?.let { runCatching { KdeLinkLifetime.valueOf(it) }.getOrNull() }
                ?: defaults.kdeConnect.lifetime,
            autoConnect = p[KDE_AUTO_CONNECT] ?: defaults.kdeConnect.autoConnect,
            clipboardReceive = p[KDE_CLIPBOARD_RECEIVE] ?: defaults.kdeConnect.clipboardReceive,
            clipboardSend = p[KDE_CLIPBOARD_SEND] ?: defaults.kdeConnect.clipboardSend,
            remoteTyping = p[KDE_REMOTE_TYPING] ?: defaults.kdeConnect.remoteTyping,
            remoteTypingPipeline = p[KDE_REMOTE_TYPING_PIPELINE]
                ?: defaults.kdeConnect.remoteTypingPipeline,
            padSensitivity = p[KDE_PAD_SENSITIVITY] ?: defaults.kdeConnect.padSensitivity,
            padAcceleration = p[KDE_PAD_ACCELERATION] ?: defaults.kdeConnect.padAcceleration,
            scrollSpeed = p[KDE_SCROLL_SPEED] ?: defaults.kdeConnect.scrollSpeed,
            naturalScroll = p[KDE_NATURAL_SCROLL] ?: defaults.kdeConnect.naturalScroll,
            tapToClick = p[KDE_TAP_TO_CLICK] ?: defaults.kdeConnect.tapToClick,
            padHaptics = p[KDE_PAD_HAPTICS] ?: defaults.kdeConnect.padHaptics,
            batteryReport = p[KDE_BATTERY_REPORT] ?: defaults.kdeConnect.batteryReport,
            exposeMedia = p[KDE_EXPOSE_MEDIA] ?: defaults.kdeConnect.exposeMedia,
            shareSheet = p[KDE_SHARE_SHEET] ?: defaults.kdeConnect.shareSheet,
            receiveFiles = p[KDE_RECEIVE_FILES] ?: defaults.kdeConnect.receiveFiles,
            composeMode = p[KDE_COMPOSE_MODE] ?: defaults.kdeConnect.composeMode,
            lastTab = p[KDE_LAST_TAB] ?: defaults.kdeConnect.lastTab,
            hosts = p[KDE_HOSTS] ?: defaults.kdeConnect.hosts,
        )

    private fun readSelfHosted(p: Preferences, defaults: KeyboardSettings) =
        SelfHostedSettings(
            libreTranslateUrl = p[SELF_HOSTED_LIBRETRANSLATE_URL]
                ?: defaults.selfHosted.libreTranslateUrl,
            libreTranslateApiKey = p[SELF_HOSTED_LIBRETRANSLATE_KEY]
                ?: defaults.selfHosted.libreTranslateApiKey,
            searxUrl = p[SELF_HOSTED_SEARX_URL] ?: defaults.selfHosted.searxUrl,
            commonsUrl = p[SELF_HOSTED_COMMONS_URL] ?: defaults.selfHosted.commonsUrl,
            endpoints = p[SELF_HOSTED_ENDPOINTS]?.let(::decodeEndpointMap) ?: defaults.selfHosted.endpoints,
            repos = p[SELF_HOSTED_REPOS]?.let(::decodeRepoMap) ?: defaults.selfHosted.repos,
        )

    /**
     * Enables or disables one tool everywhere on the keyboard. Disabling
     * leaves [KeyboardSettings.toolbarTools] untouched — the toolbar just
     * skips disabled entries, so re-enabling restores the old position.
     */
    suspend fun setToolEnabled(tool: ToolbarTool, enabled: Boolean) =
        editPrefs { prefs ->
            val disabled = decodeDisabledTools(prefs[DISABLED_TOOLS])
            val next = if (enabled) disabled - tool else (disabled + tool).distinct()
            prefs[DISABLED_TOOLS] = next.joinToString(",") { it.name }
        }

    /**
     * Leaves one tool out of the toolbox grid, or puts it back, without
     * touching whether it is on. See [ToolboxSettings.hiddenTools].
     */
    suspend fun setToolHiddenInToolbox(tool: ToolbarTool, hidden: Boolean) =
        editPrefs { prefs ->
            val current = decodeToolNames(prefs[TOOLBOX_HIDDEN_TOOLS])
            val next = if (hidden) (current + tool).distinct() else current - tool
            if (next.isEmpty()) prefs.remove(TOOLBOX_HIDDEN_TOOLS)
            else prefs[TOOLBOX_HIDDEN_TOOLS] = next.joinToString(",") { it.name }
        }

    /** Replaces the whole enabled set at once (the onboarding tools page). */
    suspend fun setEnabledTools(enabled: Collection<ToolbarTool>) =
        editPrefs { prefs ->
            prefs[DISABLED_TOOLS] =
                (ToolbarTool.entries - enabled.toSet()).joinToString(",") { it.name }
        }

    suspend fun setToolboxOrder(order: List<ToolbarTool>) =
        editPrefs {
            it[TOOLBOX_ORDER] = order.distinct().joinToString(",") { tool -> tool.name }
        }

    suspend fun setToolboxHintDismissed(value: Boolean) =
        editPrefs { it[TOOLBOX_HINT_DISMISSED] = value }

    private fun decodeDisabledTools(csv: String?): List<ToolbarTool> = decodeToolNames(csv)

    /** Comma-separated `ToolbarTool` names; anything unrecognised is dropped. */
    private fun decodeToolNames(csv: String?): List<ToolbarTool> =
        csv?.split(',')?.mapNotNull { runCatching { ToolbarTool.valueOf(it) }.getOrNull() }
            .orEmpty()

    /** `NAME=AARRGGBB` pairs; entries for unknown tools or bad hex are dropped. */
    private fun decodeToolColors(csv: String?): Map<ToolbarTool, Long> =
        csv?.split(',')?.mapNotNull { entry ->
            val parts = entry.split('=')
            if (parts.size != 2) return@mapNotNull null
            val tool = runCatching { ToolbarTool.valueOf(parts[0]) }.getOrNull()
                ?: return@mapNotNull null
            val color = parts[1].toULongOrNull(16)?.toLong() ?: return@mapNotNull null
            tool to color
        }?.toMap().orEmpty()

    private fun encodeToolColors(map: Map<ToolbarTool, Long>): String =
        map.entries.joinToString(",") { (tool, color) -> "${tool.name}=%08X".format(color) }


    /**
     * Stored order, made complete: tools the saved CSV doesn't know (added in
     * a later version, or a corrupt entry dropped) rejoin at their default
     * rank's relative position — slotted in right after the nearest
     * earlier-ranked tool the user already has, rather than all piled at the
     * very end. So a newly shipped tool lands somewhere sensible (e.g. next to
     * its peers) instead of always dead last, while nothing ever vanishes.
     */
    private fun decodeToolOrder(csv: String?): List<ToolbarTool> {
        val stored = csv?.split(',')
            ?.mapNotNull { runCatching { ToolbarTool.valueOf(it) }.getOrNull() }
            ?.distinct()
            .orEmpty()
        if (stored.isEmpty()) return DefaultToolOrder
        val storedSet = stored.toSet()
        val result = stored.toMutableList()
        // Walk the default order so multiple new tools keep their relative rank;
        // each anchors after the last already-placed tool that outranks it.
        for (tool in DefaultToolOrder) {
            if (tool in storedSet) continue
            val rank = DefaultToolOrder.indexOf(tool)
            val anchor = DefaultToolOrder.take(rank).lastOrNull { it in result }
            val at = if (anchor == null) 0 else result.indexOf(anchor) + 1
            result.add(at, tool)
        }
        return result
    }

    suspend fun setLauncherSortOrder(value: AppSortOrder) =
        editPrefs { it[LAUNCHER_SORT] = value.name }

    suspend fun setLauncherShowLabels(value: Boolean) =
        editPrefs { it[LAUNCHER_SHOW_LABELS] = value }

    suspend fun setLauncherRecentsEnabled(value: Boolean) =
        editPrefs { prefs ->
            prefs[LAUNCHER_RECENTS_ENABLED] = value
            // Turning tracking off also forgets what was tracked.
            if (!value) prefs.remove(LAUNCHER_RECENTS)
        }

    suspend fun setLauncherActivityDrilldown(value: Boolean) =
        editPrefs { it[LAUNCHER_DRILLDOWN] = value }

    suspend fun setLauncherShowNonExported(value: Boolean) =
        editPrefs { it[LAUNCHER_SHOW_NON_EXPORTED] = value }

    suspend fun setLauncherMaxRecents(value: Int) = editPrefs { prefs ->
        val cap = value.coerceIn(
            LauncherToolSettings.RECENTS_RANGE.first,
            LauncherToolSettings.RECENTS_RANGE.last,
        )
        prefs[LAUNCHER_MAX_RECENTS] = cap
        val current = prefs[LAUNCHER_RECENTS]?.split('\t')?.filter { it.isNotEmpty() }.orEmpty()
        if (current.size > cap) prefs[LAUNCHER_RECENTS] = current.take(cap).joinToString("\t")
    }

    suspend fun setCameraCaptureMaxPx(value: Int) =
        editPrefs { it[CAMERA_CAPTURE_MAX_PX] = value.coerceIn(800, 3200) }

    suspend fun setCameraTimerSeconds(value: Int) =
        editPrefs { it[CAMERA_TIMER_SECONDS] = value.coerceIn(0, 10) }

    suspend fun setPasteChipSeconds(value: Int) =
        editPrefs { it[CLIPBOARD_PASTE_CHIP_SECONDS] = value.coerceIn(0, 30 * 60) }

    /**
     * Puts the toolbox grid back to [DefaultToolOrder]. "Reset pinned tools"
     * restored the bar and nothing restored the grid, so a bad drag session
     * there had no way back.
     */
    suspend fun resetToolboxOrder() = editPrefs { it.remove(TOOLBOX_ORDER) }

    /** Records a launch at the head of the recents, newest first, capped. */
    suspend fun addLauncherRecent(packageName: String) =
        editPrefs { prefs ->
            if (prefs[LAUNCHER_RECENTS_ENABLED] == false) return@editPrefs
            val current = prefs[LAUNCHER_RECENTS]?.split('\t')?.filter { it.isNotEmpty() }
                .orEmpty()
            // Trimmed to the user's cap here as well as on read, so lowering
            // it actually drops the tail instead of hiding it.
            val cap = prefs[LAUNCHER_MAX_RECENTS] ?: LauncherToolSettings.MAX_RECENTS
            val next = (listOf(packageName) + (current - packageName)).take(cap)
            prefs[LAUNCHER_RECENTS] = next.joinToString("\t")
        }

    suspend fun toggleLauncherPin(packageName: String) =
        editPrefs { prefs ->
            val current = prefs[LAUNCHER_PINNED]?.split('\t')?.filter { it.isNotEmpty() }
                .orEmpty()
            val next = if (packageName in current) current - packageName
            else current + packageName
            prefs[LAUNCHER_PINNED] = next.joinToString("\t")
        }

    /** [LauncherToolSettings.AUTO_COLUMNS] or a count inside the range. */
    suspend fun setLauncherGridColumns(value: Int) = editPrefs {
        it[LAUNCHER_GRID_COLUMNS] = if (value == LauncherToolSettings.AUTO_COLUMNS) {
            value
        } else {
            value.coerceIn(
                LauncherToolSettings.COLUMNS_RANGE.first,
                LauncherToolSettings.COLUMNS_RANGE.last,
            )
        }
    }

    suspend fun setLauncherIconSize(value: Int) = editPrefs {
        it[LAUNCHER_ICON_SIZE] = value.coerceIn(
            LauncherToolSettings.ICON_SIZE_RANGE.first,
            LauncherToolSettings.ICON_SIZE_RANGE.last,
        )
    }

    suspend fun setLauncherIconShape(value: LauncherIconShape) =
        editPrefs { it[LAUNCHER_ICON_SHAPE] = value.name }

    suspend fun toggleLauncherHidden(packageName: String) =
        editPrefs { prefs ->
            val current = prefs[LAUNCHER_HIDDEN]?.split('\t')?.filter { it.isNotEmpty() }
                .orEmpty()
            val next = if (packageName in current) current - packageName
            else current + packageName
            prefs[LAUNCHER_HIDDEN] = next.joinToString("\t")
        }

    suspend fun clearLauncherHidden() = editPrefs { it.remove(LAUNCHER_HIDDEN) }

    suspend fun setLauncherOpenMode(value: LauncherOpenMode) =
        editPrefs { it[LAUNCHER_OPEN_MODE] = value.name }

    /** Appends a split pair; the same pair in the same order is kept once. */
    suspend fun addLauncherCombo(combo: LauncherSplitCombo) = editLauncherCombos {
        LauncherSplitCombo.add(it, combo)
    }

    suspend fun removeLauncherCombo(combo: LauncherSplitCombo) = editLauncherCombos { it - combo }

    suspend fun moveLauncherCombo(from: Int, to: Int) = editLauncherCombos {
        LauncherSplitCombo.move(it, from, to)
    }

    /** Replaces the combo at [index]: a rename or a side swap. */
    suspend fun updateLauncherCombo(index: Int, combo: LauncherSplitCombo) = editLauncherCombos {
        if (index in it.indices) it.toMutableList().apply { set(index, combo) } else it
    }

    private suspend fun editLauncherCombos(
        change: (List<LauncherSplitCombo>) -> List<LauncherSplitCombo>,
    ) = editPrefs { prefs ->
        val next = change(LauncherSplitCombo.decode(prefs[LAUNCHER_COMBOS]))
        if (next.isEmpty()) {
            prefs.remove(LAUNCHER_COMBOS)
        } else {
            prefs[LAUNCHER_COMBOS] = LauncherSplitCombo.encode(next)
        }
    }

    suspend fun setMediaPinWhilePlaying(value: Boolean) =
        editPrefs { it[MEDIA_PIN_WHILE_PLAYING] = value }

    // ---- KDE Connect (issue #285) ----

    suspend fun setKdeEnabled(value: Boolean) = editPrefs { it[KDE_ENABLED] = value }

    // Stored as typed, minus the edges; the engine strips what no desktop
    // would show. Blank falls back to the phone's model.
    suspend fun setKdeDeviceName(value: String) = editPrefs { it[KDE_DEVICE_NAME] = value.trim().take(64) }
    suspend fun setKdeLifetime(value: KdeLinkLifetime) = editPrefs { it[KDE_LIFETIME] = value.name }
    suspend fun setKdeAutoConnect(value: Boolean) = editPrefs { it[KDE_AUTO_CONNECT] = value }
    suspend fun setKdeClipboardReceive(value: Boolean) = editPrefs { it[KDE_CLIPBOARD_RECEIVE] = value }
    suspend fun setKdeClipboardSend(value: Boolean) = editPrefs { it[KDE_CLIPBOARD_SEND] = value }
    suspend fun setKdeRemoteTyping(value: Boolean) = editPrefs { it[KDE_REMOTE_TYPING] = value }
    suspend fun setKdeRemoteTypingPipeline(value: Boolean) =
        editPrefs { it[KDE_REMOTE_TYPING_PIPELINE] = value }

    suspend fun setKdePadSensitivity(value: Float) = editPrefs {
        it[KDE_PAD_SENSITIVITY] = value.coerceIn(KdeConnectSettings.PAD_SPEED_RANGE)
    }

    suspend fun setKdePadAcceleration(value: Boolean) = editPrefs { it[KDE_PAD_ACCELERATION] = value }

    suspend fun setKdeScrollSpeed(value: Float) = editPrefs {
        it[KDE_SCROLL_SPEED] = value.coerceIn(KdeConnectSettings.PAD_SPEED_RANGE)
    }

    suspend fun setKdeNaturalScroll(value: Boolean) = editPrefs { it[KDE_NATURAL_SCROLL] = value }
    suspend fun setKdeTapToClick(value: Boolean) = editPrefs { it[KDE_TAP_TO_CLICK] = value }
    suspend fun setKdePadHaptics(value: Boolean) = editPrefs { it[KDE_PAD_HAPTICS] = value }
    suspend fun setKdeBatteryReport(value: Boolean) = editPrefs { it[KDE_BATTERY_REPORT] = value }
    suspend fun setKdeExposeMedia(value: Boolean) = editPrefs { it[KDE_EXPOSE_MEDIA] = value }
    suspend fun setKdeShareSheet(value: Boolean) = editPrefs { it[KDE_SHARE_SHEET] = value }
    suspend fun setKdeReceiveFiles(value: Boolean) = editPrefs { it[KDE_RECEIVE_FILES] = value }
    suspend fun setKdeComposeMode(value: Boolean) = editPrefs { it[KDE_COMPOSE_MODE] = value }
    suspend fun setKdeLastTab(value: String) = editPrefs { it[KDE_LAST_TAB] = value }

    suspend fun addKdeHost(host: String) = editPrefs { prefs ->
        val clean = host.trim()
        if (clean.isEmpty()) return@editPrefs
        val current = prefs[KDE_HOSTS].orEmpty()
        if (current.size < KdeConnectSettings.MAX_HOSTS) prefs[KDE_HOSTS] = current + clean
    }

    suspend fun removeKdeHost(host: String) = editPrefs { prefs ->
        prefs[KDE_HOSTS] = prefs[KDE_HOSTS].orEmpty() - host
    }

    // Endpoints are stored trimmed: a URL pasted from a README arrives with
    // whitespace often enough, and the clients would otherwise build a request
    // against a host with a space in it.
    suspend fun setLibreTranslateUrl(value: String) =
        editPrefs { it[SELF_HOSTED_LIBRETRANSLATE_URL] = value.trim() }

    suspend fun setLibreTranslateApiKey(value: String) =
        editPrefs { it[SELF_HOSTED_LIBRETRANSLATE_KEY] = value.trim() }

    /**
     * One service's base address on the F-Droid build. Blank puts the service
     * back on its default. Saved as typed: a half-typed address is stored but
     * never used, see [ServiceEndpoints.resolveBase].
     */
    suspend fun setServiceEndpoint(endpoint: ServiceEndpoint, value: String) =
        editPrefs { prefs ->
            val current = prefs[SELF_HOSTED_ENDPOINTS]?.let(::decodeEndpointMap).orEmpty()
            val trimmed = value.trim()
            val next = if (trimmed.isEmpty()) current - endpoint.id else current + (endpoint.id to trimmed)
            if (next.isEmpty()) prefs.remove(SELF_HOSTED_ENDPOINTS) else prefs[SELF_HOSTED_ENDPOINTS] = encodeEndpointMap(next)
        }

    /** Where one repository is fetched from on the F-Droid build; null puts it back on its default. */
    suspend fun setServiceRepo(repo: ServiceRepo, location: RepoLocation?) =
        editPrefs { prefs ->
            val current = prefs[SELF_HOSTED_REPOS]?.let(::decodeRepoMap).orEmpty()
            val next = if (location == null) current - repo.id else current + (repo.id to location)
            if (next.isEmpty()) prefs.remove(SELF_HOSTED_REPOS) else prefs[SELF_HOSTED_REPOS] = encodeRepoMap(next)
        }

    suspend fun setSearxUrl(value: String) =
        editPrefs { it[SELF_HOSTED_SEARX_URL] = value.trim() }

    suspend fun setCommonsUrl(value: String) =
        editPrefs { it[SELF_HOSTED_COMMONS_URL] = value.trim() }

    /**
     * Ticks or unticks one package as a music player.
     *
     * Reads the stored set rather than taking the caller's copy, the way
     * [setAppLockTarget] does, so two rows toggled in the same frame cannot
     * overwrite each other. The seeded defaults are what an unset key falls
     * back to, so the first toggle materialises them before editing.
     */
    suspend fun toggleMusicApp(packageName: String, counts: Boolean) =
        editPrefs { prefs ->
            val current = prefs[MEDIA_MUSIC_APPS] ?: DefaultMusicApps
            prefs[MEDIA_MUSIC_APPS] =
                if (counts) current + packageName else current - packageName
        }

    suspend fun setMusicApps(value: Set<String>) =
        editPrefs { it[MEDIA_MUSIC_APPS] = value }

    /** Puts the music-player list back to [DefaultMusicApps]. */
    suspend fun resetMusicApps() = editPrefs { it.remove(MEDIA_MUSIC_APPS) }

    suspend fun setFlashlightAutoOff(value: Boolean) =
        editPrefs { it[FLASHLIGHT_AUTO_OFF] = value }

    suspend fun setCompassShowDegrees(value: Boolean) =
        editPrefs { it[COMPASS_SHOW_DEGREES] = value }

    suspend fun setCompassShowQibla(value: Boolean) =
        editPrefs { it[COMPASS_SHOW_QIBLA] = value }

    suspend fun setKeySoundStyle(value: KeySoundStyle) =
        editPrefs { it[KEY_SOUND_STYLE] = value.name }

    suspend fun setKeySoundVolume(value: Float) =
        editPrefs { it[KEY_SOUND_VOLUME] = value.coerceIn(0.05f, 1f) }

    /**
     * Picks an installed sound and switches the style to
     * [KeySoundStyle.CUSTOM] in one write — selecting a sound without also
     * selecting the style would look like nothing happened.
     */
    suspend fun setKeySoundCustomId(value: String) =
        editPrefs {
            it[KEY_SOUND_CUSTOM_ID] = value
            if (value.isNotBlank()) it[KEY_SOUND_STYLE] = KeySoundStyle.CUSTOM.name
        }

    /**
     * Picks an installed sound pack and switches the style to
     * [KeySoundStyle.PACK] in one write, for the same reason
     * [setKeySoundCustomId] does.
     */
    suspend fun setKeySoundPackId(value: String) =
        editPrefs {
            it[KEY_SOUND_PACK_ID] = value
            if (value.isNotBlank()) it[KEY_SOUND_STYLE] = KeySoundStyle.PACK.name
        }

    suspend fun setKeySoundPlayRelease(value: Boolean) =
        editPrefs { it[KEY_SOUND_RELEASE] = value }

    suspend fun setLevelShowAngles(value: Boolean) =
        editPrefs { it[LEVEL_SHOW_ANGLES] = value }

    suspend fun setRedoUsesCtrlY(value: Boolean) =
        editPrefs { it[REDO_USES_CTRL_Y] = value }

    suspend fun setMoonSouthernHemisphere(value: Boolean) =
        editPrefs { it[MOON_SOUTHERN] = value }

    suspend fun setNetworkLogKeep(value: Boolean) =
        editPrefs { it[NETWORK_LOG_KEEP] = value }

    suspend fun setNetworkLogOnKeyboard(value: Boolean) =
        editPrefs { it[NETWORK_LOG_ON_KEYBOARD] = value }

    suspend fun setWeatherFahrenheit(value: Boolean) =
        editPrefs { it[WEATHER_FAHRENHEIT] = value }

    suspend fun setWeatherAutoFetch(value: Boolean) =
        editPrefs { it[WEATHER_AUTO_FETCH] = value }

    /** Passing nulls clears the stored location. */
    suspend fun setWeatherLocation(latitude: Float?, longitude: Float?, place: String) =
        editPrefs { prefs ->
            if (latitude == null || longitude == null) {
                prefs.remove(WEATHER_LAT)
                prefs.remove(WEATHER_LON)
                prefs.remove(WEATHER_PLACE)
            } else {
                prefs[WEATHER_LAT] = latitude.coerceIn(-90f, 90f)
                prefs[WEATHER_LON] = longitude.coerceIn(-180f, 180f)
                prefs[WEATHER_PLACE] = place
            }
        }

    suspend fun setCalendarAltOne(value: AltCalendar) =
        editPrefs { it[CALENDAR_ALT_ONE] = value.id }

    suspend fun setCalendarAltTwo(value: AltCalendar) =
        editPrefs { it[CALENDAR_ALT_TWO] = value.id }

    suspend fun setCalendarWeekend(value: Weekend) =
        editPrefs { it[CALENDAR_WEEKEND] = value.id }

    /**
     * The device's region, for the settings whose sensible default depends on
     * where the phone is rather than on a value anyone could pick globally.
     *
     * Read once and kept: `mapPreferences` runs on every settings emission, and
     * the SIM is not going to change between two of them.
     */
    val deviceRegion: String? by lazy {
        DeviceLocales.read(context).regionCodes.firstOrNull()
    }

    /**
     * The clipboard's phone masks before the user has touched the list: the one
     * shape their own country's numbers have, or nothing at all for a region
     * [PhoneFormats.forRegion] does not know.
     *
     * Only ever the *starting* list. Adding or deleting a mask writes the key,
     * and once written it wins — including when the user deletes the seeded one
     * and leaves the list empty, which is the old behaviour of offering every
     * number-shaped run and is a choice they are allowed to make.
     */
    private fun seededPhoneFormats(): Set<String> =
        PhoneFormats.forRegion(deviceRegion)?.let { setOf(it) } ?: emptySet()

    /**
     * One of the two alternate-calendar slots.
     *
     * With no stored pick, the pair comes from the device's region, so a phone
     * in Bangladesh opens the tool on the Bengali calendar and one in Japan on
     * the Japanese era years, rather than everyone getting Bangladesh's. A
     * region with no calendar worth showing gets none, since two extra numbers
     * per cell are noise to whoever reads neither.
     *
     * Installs from before the picker existed have no stored pick either, but do
     * have the old Bengali/Hijri switches, and those win over the region: they
     * are a choice someone actually made.
     */
    private fun calendarAltFromPrefs(p: Preferences, first: Boolean): AltCalendar {
        p[if (first) CALENDAR_ALT_ONE else CALENDAR_ALT_TWO]?.let { return AltCalendar.fromId(it) }
        val bengali = p[CALENDAR_SHOW_BENGALI]
        val hijri = p[CALENDAR_SHOW_HIJRI]
        if (bengali == null && hijri == null) {
            val (one, two) = defaultAltCalendars(deviceRegion)
            return if (first) one else two
        }
        val legacy = buildList {
            if (bengali != false) add(AltCalendar.BENGALI)
            if (hijri != false) add(AltCalendar.HIJRI)
        }
        return legacy.getOrElse(if (first) 0 else 1) { AltCalendar.NONE }
    }

    suspend fun setHijriAdjustDays(value: Int) =
        editPrefs { it[HIJRI_ADJUST_DAYS] = value.coerceIn(-2, 2) }

    suspend fun setHandwritingStylusOnly(value: Boolean) =
        editPrefs { it[HANDWRITING_STYLUS_ONLY] = value }

    suspend fun setHandwritingCommitDelayMs(value: Int) =
        editPrefs { it[HANDWRITING_COMMIT_DELAY] = value.coerceIn(300, 2000) }

    suspend fun setHandwritingAutoSpace(value: Boolean) =
        editPrefs { it[HANDWRITING_AUTO_SPACE] = value }

    suspend fun setVoiceUiMode(value: String) =
        editPrefs {
            it[VOICE_UI_MODE] = value
            // A settings-app choice, so the bar wears its keyboard button —
            // only [setVoiceSurface]'s inline collapse sets this true.
            it[VOICE_BAR_INLINE] = false
        }

    suspend fun setVoiceTypingMode(value: String) =
        editPrefs { it[VOICE_TYPING_MODE] = value }

    suspend fun setVoiceHoldPicksTypingMode(value: Boolean) =
        editPrefs { it[VOICE_HOLD_PICKS_MODE] = value }

    suspend fun setVoiceBarActive(value: Boolean) =
        editPrefs { it[VOICE_BAR_ACTIVE] = value }

    suspend fun setVoiceBarVertical(value: Boolean) =
        editPrefs { it[VOICE_BAR_VERTICAL] = value }

    /** The bar settled after a drag: its whole resting place, in one write. */
    suspend fun setVoiceBarRest(snap: Int, rightEdge: Boolean, yBias: Float, dockBias: Float) =
        editPrefs {
            it[VOICE_BAR_SNAP] = snap
            it[VOICE_BAR_EDGE_RIGHT] = rightEdge
            it[VOICE_BAR_Y_BIAS] = yBias.coerceIn(0f, 1f)
            it[VOICE_BAR_DOCK_BIAS] = dockBias.coerceIn(0f, 1f)
        }

    /**
     * An inline switch between the voice surfaces (the panel's and strip's
     * collapse buttons, the bar's expand button): the mode, the bar's armed
     * flag and the surface to return to move together, in one write, so a
     * settings emission can never see half a switch.
     */
    suspend fun setVoiceSurface(mode: String, barActive: Boolean, returnMode: String? = null) =
        editPrefs {
            it[VOICE_UI_MODE] = mode
            it[VOICE_BAR_ACTIVE] = barActive
            it[VOICE_BAR_INLINE] = mode == VoiceBarSettings.MODE_BAR
            if (returnMode != null) it[VOICE_UI_RETURN_MODE] = returnMode
        }

    suspend fun setVoiceContinuous(value: Boolean) =
        editPrefs { it[VOICE_CONTINUOUS] = value }

    suspend fun setVoiceSpokenPunctuation(value: Boolean) =
        editPrefs { it[VOICE_SPOKEN_PUNCTUATION] = value }

    suspend fun setVoiceEngine(value: String) =
        editPrefs { it[VOICE_ENGINE] = value }

    suspend fun setWhisperModelId(value: String) =
        editPrefs { it[WHISPER_MODEL_ID] = value }

    /**
     * Pins [languageId] to a Whisper model, or drops the entry when [modelId] is
     * blank so that language goes back to being resolved automatically.
     */
    suspend fun setWhisperModelForLanguage(languageId: String, modelId: String) =
        editPrefs { prefs ->
            val current = prefs[WHISPER_MODEL_BY_LANG]?.let { decodeWhisperModelByLang(it) }.orEmpty()
            val next =
                if (modelId.isBlank()) current - languageId else current + (languageId to modelId)
            if (next == current) return@editPrefs
            prefs[WHISPER_MODEL_BY_LANG] = encodeWhisperModelByLang(next)
        }

    /** Drops every language pinned to [modelId] — used when that model is deleted. */
    suspend fun clearWhisperModelAssignments(modelId: String) =
        editPrefs { prefs ->
            val current = prefs[WHISPER_MODEL_BY_LANG]?.let { decodeWhisperModelByLang(it) }.orEmpty()
            val next = current.filterValues { it != modelId }
            if (next == current) return@editPrefs
            prefs[WHISPER_MODEL_BY_LANG] = encodeWhisperModelByLang(next)
        }

    suspend fun setWhisperTranslate(value: Boolean) =
        editPrefs { it[WHISPER_TRANSLATE] = value }

    suspend fun setVoiceServerUrl(value: String) =
        editPrefs { it[VOICE_SERVER_URL] = value.trim().trimEnd('/') }

    suspend fun setVoiceServerKey(value: String) =
        editPrefs { it[VOICE_SERVER_KEY] = value.trim() }

    suspend fun setVoiceServerModel(value: String) =
        editPrefs { it[VOICE_SERVER_MODEL] = value.trim() }

    suspend fun setVoiceServerSendLanguage(value: Boolean) =
        editPrefs { it[VOICE_SERVER_SEND_LANGUAGE] = value }

    suspend fun setVoiceBiasPersonalWords(value: Boolean) =
        editPrefs { it[VOICE_BIAS_PERSONAL_WORDS] = value }

    suspend fun setVoiceBiasWords(value: String) =
        editPrefs { it[VOICE_BIAS_WORDS] = value }

    suspend fun setVoiceServerPrompt(value: String) =
        editPrefs { it[VOICE_SERVER_PROMPT] = value }

    suspend fun setCameraPreferFront(value: Boolean) =
        editPrefs { it[CAMERA_PREFER_FRONT] = value }

    suspend fun setCameraMirrorFront(value: Boolean) =
        editPrefs { it[CAMERA_MIRROR_FRONT] = value }

    suspend fun setCameraShutterSound(value: Boolean) =
        editPrefs { it[CAMERA_SHUTTER_SOUND] = value }

    suspend fun setCameraHaptics(value: Boolean) =
        editPrefs { it[CAMERA_HAPTICS] = value }

    suspend fun setCameraSaveToGallery(value: Boolean) =
        editPrefs { it[CAMERA_SAVE_TO_GALLERY] = value }

    suspend fun setCameraFullFrame(value: Boolean) =
        editPrefs { it[CAMERA_FULL_FRAME] = value }

    suspend fun setCameraSearchButton(value: Boolean) =
        editPrefs { it[CAMERA_SEARCH_BUTTON] = value }

    suspend fun setCameraSearchWith(value: PhotoSearchTarget) =
        editPrefs { it[CAMERA_SEARCH_WITH] = value.name }

    suspend fun setCameraSearchEngine(value: PhotoSearchEngine) =
        editPrefs { it[CAMERA_SEARCH_ENGINE] = value.name }

    suspend fun setCameraSearchCustomUrl(value: String) =
        editPrefs { it[CAMERA_SEARCH_CUSTOM_URL] = value.trim() }

    suspend fun setCameraSearchCustomField(value: String) =
        editPrefs { it[CAMERA_SEARCH_CUSTOM_FIELD] = value.trim() }

    suspend fun setDocScanSaveToGallery(value: Boolean) =
        editPrefs { it[DOC_SCAN_SAVE_TO_GALLERY] = value }

    suspend fun setQrSaveToGallery(value: Boolean) =
        editPrefs { it[QR_SAVE_TO_GALLERY] = value }

    suspend fun setStickerSendMode(value: MediaSendMode) =
        editPrefs { it[STICKER_SEND_MODE] = value.name }

    suspend fun setGifSendMode(value: MediaSendMode) =
        editPrefs { it[GIF_SEND_MODE] = value.name }

    suspend fun setQrSendMode(value: MediaSendMode) =
        editPrefs { it[QR_SEND_MODE] = value.name }

    suspend fun setTextEditRepeatMs(value: Int) =
        editPrefs { it[TEXT_EDIT_REPEAT_MS] = value.coerceIn(30, 200) }

    suspend fun setCursorToolsRepeatOnHold(value: Boolean) =
        editPrefs { it[CURSOR_TOOLS_REPEAT_ON_HOLD] = value }

    /**
     * Adds or removes one cursor tool from the toolbox's repeat set. Read-modify
     * -write inside the same edit, so two tools switched on in quick succession
     * cannot each overwrite the other's row.
     */
    suspend fun setToolboxRepeat(tool: ToolbarTool, repeat: Boolean) =
        editPrefs { prefs ->
            val current = decodeToolNames(prefs[TOOLBOX_REPEAT_TOOLS]).toMutableSet()
            if (repeat) current += tool else current -= tool
            prefs[TOOLBOX_REPEAT_TOOLS] = current.joinToString(",") { it.name }
        }

    suspend fun setSelectionModeHold(value: Boolean) =
        editPrefs { it[SELECTION_MODE_HOLD] = value }

    suspend fun setSelectionModeMultiTap(value: Boolean) =
        editPrefs { it[SELECTION_MODE_MULTI_TAP] = value }

    suspend fun setDoubleSpaceWindowMs(value: Int) =
        editPrefs { it[DOUBLE_SPACE_WINDOW_MS] = value.coerceIn(200, 800) }

    suspend fun setSpaceCursorStepDp(value: Int) =
        editPrefs { it[SPACE_CURSOR_STEP_DP] = value.coerceIn(8, 32) }

    suspend fun setSpaceCursorMagnifier(value: Boolean) =
        editPrefs { it[SPACE_CURSOR_MAGNIFIER] = value }

    suspend fun setBackspaceWordStepDp(value: Int) =
        editPrefs { it[BACKSPACE_WORD_STEP_DP] = value.coerceIn(32, 120) }

    suspend fun setBackspaceSwipeUnit(value: BackspaceSwipeUnit) =
        editPrefs { it[BACKSPACE_SWIPE_UNIT] = value.name }

    suspend fun setBackspaceSwipePreview(value: Boolean) =
        editPrefs { it[BACKSPACE_SWIPE_PREVIEW] = value }

    suspend fun setDeleteHoldDeletesWords(value: Boolean) =
        editPrefs { it[DELETE_HOLD_DELETES_WORDS] = value }

    suspend fun setForwardDeleteSwipe(value: Boolean) =
        editPrefs { it[FORWARD_DELETE_SWIPE] = value }

    suspend fun setBackspaceCharStepDp(value: Int) =
        editPrefs { it[BACKSPACE_CHAR_STEP_DP] = value.coerceIn(8, 48) }

    suspend fun setTrackpadStepXDp(value: Int) =
        editPrefs { it[TRACKPAD_STEP_X_DP] = value.coerceIn(4, 48) }

    suspend fun setTrackpadStepYDp(value: Int) =
        editPrefs { it[TRACKPAD_STEP_Y_DP] = value.coerceIn(8, 96) }

    suspend fun setTrackpadHoldToOpen(value: Boolean) =
        editPrefs { it[TRACKPAD_HOLD_TO_OPEN] = value }

    suspend fun setTrackpadMultiTap(value: Boolean) =
        editPrefs { it[TRACKPAD_MULTI_TAP] = value }

    suspend fun setTrackpadHaptics(value: Boolean) =
        editPrefs { it[TRACKPAD_HAPTICS] = value }

    suspend fun setTrackpadTrail(value: Boolean) =
        editPrefs { it[TRACKPAD_TRAIL] = value }

    suspend fun setTrackpadMagnifier(value: Boolean) =
        editPrefs { it[TRACKPAD_MAGNIFIER] = value }

    suspend fun setVocabNudges(value: Boolean) = editPrefs { it[VOCAB_NUDGES] = value }

    suspend fun setVocabNudgeOnVocabWord(value: Boolean) = editPrefs { it[VOCAB_NUDGE_SELF] = value }

    suspend fun setVocabNudgeScope(value: VocabNudgeScope) = editPrefs { it[VOCAB_NUDGE_SCOPE] = value.name }

    suspend fun setVocabNudgeLevel(value: VocabNudgeLevel) = editPrefs { it[VOCAB_NUDGE_LEVEL] = value.name }

    suspend fun setVocabCooldown(value: VocabCooldown) = editPrefs { it[VOCAB_COOLDOWN] = value.name }

    suspend fun setVocabChipTapAction(value: VocabChipTap) = editPrefs { it[VOCAB_CHIP_TAP] = value.name }

    suspend fun setVocabRelatedTap(value: VocabRelatedTap) = editPrefs { it[VOCAB_RELATED_TAP] = value.name }

    suspend fun setVocabScheduler(value: VocabScheduler) = editPrefs { it[VOCAB_SCHEDULER] = value.name }

    suspend fun setVocabDailyGoal(value: Int) = editPrefs {
        it[VOCAB_DAILY_GOAL] = value.coerceIn(VocabularySettings.MIN_DAILY_GOAL, VocabularySettings.MAX_DAILY_GOAL)
    }

    suspend fun setVocabWordOfTheDayCard(value: Boolean) = editPrefs { it[VOCAB_WOTD_CARD] = value }

    suspend fun setVocabWordOfTheDayChip(value: Boolean) = editPrefs { it[VOCAB_WOTD_CHIP] = value }

    suspend fun setVocabWordInterval(value: VocabWordInterval) = editPrefs { it[VOCAB_WORD_INTERVAL] = value.name }

    suspend fun setVocabChipTimesPerWord(value: Int) =
        editPrefs { it[VOCAB_CHIP_TIMES] = value.coerceIn(VocabularySettings.MIN_CHIP_TIMES, VocabularySettings.MAX_CHIP_TIMES) }

    suspend fun setVocabAudioSource(value: VocabAudioSource) = editPrefs { it[VOCAB_AUDIO_SOURCE] = value.name }

    suspend fun setVocabAccent(value: VocabAccent) = editPrefs { it[VOCAB_ACCENT] = value.name }

    suspend fun setVocabTtsRate(value: Float) = editPrefs {
        it[VOCAB_TTS_RATE] = value.coerceIn(VocabularySettings.MIN_TTS, VocabularySettings.MAX_TTS)
    }

    suspend fun setVocabTtsPitch(value: Float) = editPrefs {
        it[VOCAB_TTS_PITCH] = value.coerceIn(VocabularySettings.MIN_TTS, VocabularySettings.MAX_TTS)
    }

    suspend fun setVocabCardFields(value: String) = editPrefs { it[VOCAB_CARD_FIELDS] = value }

    suspend fun setVocabTranslationLangs(value: String) = editPrefs { it[VOCAB_TRANSLATION_LANGS] = value }

    suspend fun setNumpadCalculatorLayout(value: Boolean) =
        editPrefs {
            it[NUMPAD_CALCULATOR_LAYOUT] = value
            it.remove(NUMPAD_PHONE_LAYOUT)
        }

    suspend fun setIncognitoPausesClipboard(value: Boolean) =
        editPrefs { it[INCOGNITO_PAUSES_CLIPBOARD] = value }

    suspend fun setIncognitoPausesLearning(value: Boolean) =
        editPrefs { it[INCOGNITO_PAUSES_LEARNING] = value }

    suspend fun setAutoIncognito(value: Boolean) =
        editPrefs { it[AUTO_INCOGNITO] = value }

    suspend fun setCloudBackup(value: Boolean) =
        editPrefs { it[CloudBackup.KEY] = value }

    suspend fun setOcrAutoSelectWords(value: Boolean) =
        editPrefs { it[OCR_AUTO_SELECT_WORDS] = value }

    suspend fun setOcrEngine(value: OcrEngine) =
        editPrefs { it[OCR_ENGINE] = value.name }

    suspend fun setQrScanHaptics(value: Boolean) =
        editPrefs { it[QR_SCAN_HAPTICS] = value }

    suspend fun setQrScanAutoInsert(value: Boolean) =
        editPrefs { it[QR_SCAN_AUTO_INSERT] = value }

    suspend fun setQrScanLinkPreviews(value: Boolean) =
        editPrefs { it[QR_SCAN_LINK_PREVIEWS] = value }

    suspend fun setCurrencyDecimals(value: Int) =
        editPrefs { it[CURRENCY_DECIMALS] = value.coerceIn(0, 6) }

    suspend fun setCurrencyLabel(value: CurrencyLabel) =
        editPrefs { it[CURRENCY_LABEL] = value.name }

    suspend fun setCurrencyCacheHours(value: Int) =
        editPrefs { it[CURRENCY_CACHE_HOURS] = value.coerceIn(1, 48) }

    suspend fun setCurrencyAutoFetch(value: Boolean) =
        editPrefs { it[CURRENCY_AUTO_FETCH] = value }

    suspend fun setCryptoEnabled(value: Boolean) =
        editPrefs { it[CRYPTO_ENABLED] = value }

    suspend fun setCryptoCacheMinutes(value: Int) =
        editPrefs { it[CRYPTO_CACHE_MINUTES] = value.coerceIn(1, 60) }

    /** 0 keeps significant digits instead of a fixed count. */
    suspend fun setCryptoDecimals(value: Int) =
        editPrefs { it[CRYPTO_DECIMALS] = value.coerceIn(0, 12) }

    /**
     * The rate sources to try, best first. An empty list would leave the
     * tool with nowhere to fetch from, so it clears the setting and lets the
     * defaults stand instead.
     */
    suspend fun setFiatProviders(value: List<String>) =
        editPrefs { prefs -> writeProviders(prefs, FIAT_PROVIDERS, value) }

    suspend fun setCryptoProviders(value: List<String>) =
        editPrefs { prefs -> writeProviders(prefs, CRYPTO_PROVIDERS, value) }

    private fun writeProviders(
        prefs: MutablePreferences,
        key: Preferences.Key<String>,
        value: List<String>,
    ) {
        val cleaned = value.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) prefs.remove(key) else prefs[key] = cleaned.joinToString("\n")
    }

    /**
     * Turn one coin on or off. An empty set means "the catalogue defaults",
     * so switching the last coin off would silently turn them all back on —
     * the caller has to keep at least one, and the panel's chip grid does.
     */
    suspend fun setCryptoTickers(value: Set<String>) =
        editPrefs { prefs ->
            val cleaned = value.mapNotNull(::normalizeTicker).toSet()
            if (cleaned.isEmpty()) prefs.remove(CRYPTO_TICKERS) else prefs[CRYPTO_TICKERS] = cleaned
        }

    /** Tickers are letters and digits in capitals: "btc " becomes "BTC". */
    private fun normalizeTicker(raw: String): String? =
        raw.trim().uppercase().filter { it.isLetterOrDigit() }.take(12).ifEmpty { null }

    suspend fun setGrammarDebounceMs(value: Int) =
        editPrefs { it[GRAMMAR_DEBOUNCE_MS] = value.coerceIn(100, 1500) }

    suspend fun setUnitConvertLast(value: String) =
        editPrefs { it[UNIT_CONVERT_LAST] = value }

    suspend fun setCompoundUnits(value: Boolean) =
        editPrefs { it[COMPOUND_UNITS] = value }

    suspend fun setDictionaryAutoLookup(value: Boolean) =
        editPrefs { it[DICTIONARY_AUTO_LOOKUP] = value }

    /** Replaces the Dictionary tool's sources, order and switches together. */
    suspend fun setDictionarySources(value: List<DictionarySourceChoice>) =
        editPrefs { it[DICTIONARY_SOURCES] = DictionarySources.encode(value) }

    suspend fun setToolboxColumns(value: Int) =
        editPrefs { it[TOOLBOX_COLUMNS] = value.coerceIn(3, 6) }

    suspend fun setToolboxLayout(value: ToolboxLayout) =
        editPrefs { it[TOOLBOX_LAYOUT] = value.name }

    suspend fun setToolboxPillColumns(value: Int) =
        editPrefs { it[TOOLBOX_PILL_COLUMNS] = value.coerceIn(1, 3) }

    suspend fun setToolboxPillFilled(value: Boolean) =
        editPrefs { it[TOOLBOX_PILL_FILLED] = value }

    suspend fun setToolboxPaginate(value: Boolean) =
        editPrefs { it[TOOLBOX_PAGINATE] = value }

    suspend fun setToolboxPageSize(value: Int) =
        editPrefs { it[TOOLBOX_PAGE_SIZE] = value.coerceIn(ToolboxPageSizeRange) }

    /** 0 means "follow the toolbar label size"; see [ToolboxSettings.labelSizeSp]. */
    suspend fun setToolboxLabelSize(value: Int) =
        editPrefs { it[TOOLBOX_LABEL_SIZE] = if (value <= 0) 0 else value.coerceIn(7, 16) }

    suspend fun setSuggestionTextScale(value: Float) =
        editPrefs { it[SUGGESTION_TEXT_SCALE] = value.coerceIn(0.8f, 1.6f) }

    suspend fun setLearnedWordMinCount(value: Int) =
        editPrefs { it[LEARNED_WORD_MIN_COUNT] = value.coerceIn(1, 5) }

    suspend fun setNewWordSightings(value: Int) =
        editPrefs { it[NEW_WORD_SIGHTINGS] = value.coerceIn(1, 10) }

    suspend fun setAskBeforeLearning(value: Boolean) =
        editPrefs { it[ASK_BEFORE_LEARNING] = value }

    suspend fun setOfferNearMissCorrections(value: Boolean) =
        editPrefs { it[OFFER_NEAR_MISS_CORRECTIONS] = value }

    suspend fun setUndoCorrectionChip(value: Boolean) =
        editPrefs { it[UNDO_CORRECTION_CHIP] = value }

    suspend fun setUndoChipObviousness(value: Float) =
        editPrefs { it[UNDO_CHIP_OBVIOUSNESS] = value.coerceIn(0f, 1f) }

    suspend fun setLearnFromCorrections(value: Boolean) =
        editPrefs { it[LEARN_FROM_CORRECTIONS] = value }

    suspend fun setAdaptToTaps(value: Boolean) =
        editPrefs { it[ADAPT_TO_TAPS] = value }

    /**
     * The Learned-corrections screen changed the learned corrections or the
     * tap model on disk: tell a running keyboard to drop its copy — without
     * the lexicon signal, whose reload also empties the keyboard's
     * half-learned words.
     */
    suspend fun bumpCorrectionsVersion() =
        editPrefs { it[CORRECTIONS_VERSION] = (it[CORRECTIONS_VERSION] ?: 0) + 1 }

    /** Deletes everything autocorrect learned from the user's own fixes. */
    suspend fun forgetLearnedCorrections() {
        runCatching { File(context.filesDir, LEARNED_CORRECTIONS_FILE).delete() }
        bumpCorrectionsVersion()
    }

    /** Deletes the learned tap model, the way [forgetSwipeStyle] does the swipe style. */
    suspend fun forgetTapModel() {
        runCatching { File(context.filesDir, TAP_MODEL_FILE).delete() }
        bumpCorrectionsVersion()
    }

    suspend fun setEmojiRowAboveToolbar(value: Boolean) =
        editPrefs { it[EMOJI_ROW_ABOVE_TOOLBAR] = value }

    suspend fun setToolbarTools(tools: List<ToolbarTool>) =
        editPrefs {
            it[TOOLBAR_TOOLS] = tools.distinct().joinToString(",") { tool -> tool.name }
        }

    suspend fun setToolbarGreedy(value: Boolean) =
        editPrefs { it[TOOLBAR_GREEDY] = value }

    suspend fun setToolbarEnabled(value: Boolean) =
        editPrefs { it[TOOLBAR_ENABLED] = value }

    suspend fun setToolbarSwipeDownHide(value: Boolean) =
        editPrefs { it[TOOLBAR_SWIPE_DOWN_HIDE] = value }

    suspend fun setToolbarDragToRearrange(value: Boolean) =
        editPrefs { it[TOOLBAR_DRAG_REARRANGE] = value }

    suspend fun setToolbarOnlyWithHardwareKeyboard(value: Boolean) =
        editPrefs { it[TOOLBAR_ONLY_HW_KEYBOARD] = value }

    suspend fun setReverseToolbarForRtl(value: Boolean) =
        editPrefs { it[REVERSE_TOOLBAR_RTL] = value }

    suspend fun setToolbarHeightDp(value: Int) =
        editPrefs { it[TOOLBAR_HEIGHT] = value.coerceIn(32, 80) }

    suspend fun setToolbarScrollable(value: Boolean) =
        editPrefs { it[TOOLBAR_SCROLLABLE] = value }

    suspend fun setToolbarHideWhenLocked(value: Boolean) =
        editPrefs { it[TOOLBAR_HIDE_WHEN_LOCKED] = value }

    suspend fun setToolbarLabels(value: Boolean) =
        editPrefs { it[TOOLBAR_LABELS] = value }

    suspend fun setToolbarLabelSize(value: Int) =
        editPrefs { it[TOOLBAR_LABEL_SIZE] = value.coerceIn(7, 14) }

    suspend fun setToolCircleRadiusDp(value: Int) =
        editPrefs { it[TOOL_CIRCLE_RADIUS] = value.coerceIn(0, 20) }

    suspend fun setToolShape(value: KeyShapeKind) =
        editPrefs { it[TOOL_SHAPE] = value.name }

    suspend fun setToolbarToolWidthDp(value: Int) =
        editPrefs { it[TOOLBAR_TOOL_WIDTH] = value.coerceIn(38, 64) }

    suspend fun setToolbarPaddingTopDp(value: Int) =
        editPrefs { it[TOOLBAR_PADDING_TOP] = value.coerceIn(0, 24) }

    suspend fun setToolbarPaddingBottomDp(value: Int) =
        editPrefs { it[TOOLBAR_PADDING_BOTTOM] = value.coerceIn(0, 24) }

    suspend fun setToolbarPlacement(value: ToolbarPlacement) =
        editPrefs { it[TOOLBAR_PLACEMENT] = value.name }

    suspend fun setToolbarShowStrip(value: Boolean) =
        editPrefs { it[TOOLBAR_SHOW_STRIP] = value }

    /**
     * Sets or clears one tool's press-and-hold action. Null puts that tool back
     * to opening its own settings page; [ToolHoldAction.None] makes the hold do
     * nothing at all.
     */
    suspend fun setToolHoldAction(tool: ToolbarTool, action: ToolHoldAction?) =
        editPrefs { prefs ->
            val current = ToolHoldActions.decode(prefs[TOOLBAR_HOLD_ACTIONS]).toMutableMap()
            val selfBound = action is ToolHoldAction.Run && action.tool == tool
            if (action == null || selfBound) current.remove(tool) else current[tool] = action
            prefs[TOOLBAR_HOLD_ACTIONS] = ToolHoldActions.encode(current)
        }

    /**
     * Moving emoji onto the comma key also pulls the emoji tool off the
     * toolbar (it would be redundant); the user can drag it back from the
     * toolbox. Turning the setting off leaves the toolbar as-is.
     */
    suspend fun setCommaAsEmoji(value: Boolean) =
        editPrefs { prefs ->
            prefs[COMMA_AS_EMOJI] = value
            if (value) {
                val current = prefs[TOOLBAR_TOOLS]
                    ?.split(',')
                    ?.mapNotNull { runCatching { ToolbarTool.valueOf(it) }.getOrNull() }
                    ?: KeyboardSettings().toolbarTools
                prefs[TOOLBAR_TOOLS] = current.filter { it != ToolbarTool.EMOJI }
                    .joinToString(",") { it.name }
            }
        }

    suspend fun setSwapCommaAndGlobe(value: Boolean) =
        editPrefs { it[SWAP_COMMA_GLOBE] = value }

    /**
     * Switches the active layout.
     *
     * Repairs it on the way in — the one place that has to, along with import.
     * Ordinary saves deliberately do not, so the editor can hold a half-built
     * grid; but the moment a layout becomes the thing you type on it must have
     * a delete key, because you cannot fix the typo that lost you the key.
     *
     * [recentFrom] records the switch in the recently-used list (#311), in the
     * same edit so a switch costs one settings emission, not two. Null for
     * writes that are not the user switching (restores, repairs).
     */
    suspend fun setActiveLayoutId(id: String, recentFrom: String? = null) =
        editPrefs { prefs ->
            val custom = prefs[CUSTOM_LAYOUTS]?.let { LayoutCodec.decodeList(it) }.orEmpty()
            val stored = custom.firstOrNull { it.id == id }
            // resolveLayout falls back to the default, so an id whose layout was
            // deleted heals here rather than selecting nothing.
            val repaired = resolveLayout(custom, id).repair().spec
            // Only write back when there was a stored layout and the repair
            // actually changed it; an untouched built-in needs no write.
            if (stored != null && repaired != stored) {
                prefs[CUSTOM_LAYOUTS] =
                    LayoutCodec.encodeList(custom.filter { it.id != id } + repaired)
            }
            prefs[ACTIVE_LAYOUT_ID] = repaired.id
            if (recentFrom != null) {
                val recent = prefs[RECENT_LAYOUT_IDS]?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
                prefs[RECENT_LAYOUT_IDS] =
                    rememberLayoutSwitch(recent, recentFrom, repaired.id).joinToString(",")
            }
        }

    suspend fun setRawClipboardShortcuts(value: Boolean) =
        editPrefs { it[RAW_CLIPBOARD_SHORTCUTS] = value }

    /** Replaces the secondary-language map (primary langId → secondary langIds). */
    suspend fun setSecondaryLanguages(map: Map<String, List<String>>) =
        editPrefs { it[SECONDARY_LANGUAGES] = encodeSecondaryLanguages(map) }

    /**
     * Cross-wires every enabled romanized language with the enabled languages
     * of the same script, both directions (see [RomanizedPairing]). Returns
     * the newly linked pairs as unordered language-id pairs — empty when
     * everything was already wired — so the Languages screen can toast what
     * happened while onboarding stays silent. Only ever adds links; callers
     * run it at the moments auto-pairing is documented to apply (a language
     * was just added, or the one-shot upgrade reconcile).
     *
     * [addedLanguageId] limits it to that language's pairs, so a link the user
     * removed between two languages already on the list is not brought back.
     * Null wires every pair, which only the upgrade reconcile wants.
     */
    suspend fun autoPairRomanizedSecondaries(
        addedLanguageId: String? = null,
    ): List<Pair<String, String>> {
        val current = settings.first()
        // Off means a link the user removed by hand stays removed, instead of
        // coming back the next time any language is added.
        if (!current.autoPairRomanized) return emptyList()
        val result = RomanizedPairing.autoPair(
            current.enabledLanguages,
            current.secondaryLanguages,
            involving = addedLanguageId?.let(::setOf),
        )
        if (result.added.isEmpty()) return emptyList()
        setSecondaryLanguages(result.secondaries)
        return result.addedPairs
    }

    /**
     * The upgrade path for installs that enabled a romanized pair before
     * auto-pairing existed: runs [autoPairRomanizedSecondaries] exactly once
     * per install. Behind a flag rather than folded into every read so that
     * a link the user deliberately removes afterwards stays removed.
     */
    suspend fun reconcileRomanizedSecondariesOnce() {
        if (context.dataStore.data.first()[AUTO_PAIR_ROMANIZED_DONE] == true) return
        autoPairRomanizedSecondaries()
        editPrefs { it[AUTO_PAIR_ROMANIZED_DONE] = true }
    }

    /** The layouts the 🌐 key cycles; an empty pick falls back to the default. */
    suspend fun setEnabledLayoutIds(ids: List<String>) =
        editPrefs { prefs ->
            val next = ids.distinct().ifEmpty { listOf(BuiltInLayouts.DEFAULT_ID) }
            prefs[ENABLED_LAYOUT_IDS] = next.joinToString(",")
            // Shrinking the cycle can strand the active layout outside it —
            // removing a language whose layout is current would otherwise keep
            // the keyboard typing in the language the user just removed. Snap
            // to the first remaining stop. A null active is left null on
            // purpose: nothing has been picked yet, and `resolveLayoutSelection`
            // reads that as "the head of the cycle", which is already the stop
            // this would snap to. Writing an id here instead would freeze the
            // seeded language in place the first time the list is edited.
            val active = prefs[ACTIVE_LAYOUT_ID]
            if (active != null && active !in next) prefs[ACTIVE_LAYOUT_ID] = next.first()
        }

    /**
     * Adds a layout, or replaces the stored one with the same id.
     *
     * Deliberately does *not* repair. The editor saves on every keystroke, so
     * repairing here would re-add a delete key the instant the user removed a
     * row, and the undo stack would record the repaired grid rather than the one
     * being built. Repair belongs at the two moments the layout leaves the
     * user's hands: import, and [setActiveLayoutId].
     */
    suspend fun upsertCustomLayout(layout: LayoutSpec) =
        editPrefs { prefs ->
            val current = prefs[CUSTOM_LAYOUTS]?.let { LayoutCodec.decodeList(it) }.orEmpty()
            prefs[CUSTOM_LAYOUTS] =
                LayoutCodec.encodeList(current.filter { it.id != layout.id } + layout)
        }

    /**
     * Applies [transform] to the stored layout, reading it inside the same edit.
     *
     * The editor saves on every keystroke, and the layout it holds comes from
     * the settings flow, which lags the write it just made. Handing back a whole
     * layout built from that stale copy loses the previous edit whenever two land
     * within a frame of each other — type a label, nudge a width, and the label
     * comes back. Reading inside the edit makes each change apply to what is
     * actually stored.
     *
     * An id with no stored layout resolves to the built-in of that id, so the
     * first edit to an inherited built-in writes the override rather than
     * silently doing nothing.
     */
    suspend fun updateCustomLayout(id: String, transform: (LayoutSpec) -> LayoutSpec) =
        editPrefs { prefs ->
            val current = prefs[CUSTOM_LAYOUTS]?.let { LayoutCodec.decodeList(it) }.orEmpty()
            val next = transform(resolveLayout(current, id))
            prefs[CUSTOM_LAYOUTS] =
                LayoutCodec.encodeList(current.filter { it.id != next.id } + next)
        }

    /**
     * Deletes a custom layout and drops every reference to it.
     *
     * Deleting an *edited shipped layout* only removes the override — the
     * shipped grid comes back under the same id, so every reference to it stays
     * valid, which is why the reference cleanup below is skipped for those.
     * "Shipped" covers the JSON asset layouts as well as the compiled built-ins:
     * `findLayout` puts both back, so an edited BÉPO restores exactly
     * like an edited QWERTY does, and stripping its references would switch off
     * a layout that is still there.
     */
    suspend fun deleteCustomLayout(id: String) =
        editPrefs { prefs ->
            val current = prefs[CUSTOM_LAYOUTS]?.let { LayoutCodec.decodeList(it) }.orEmpty()
            prefs[CUSTOM_LAYOUTS] = LayoutCodec.encodeList(current.filter { it.id != id })
            if (isShippedLayoutId(id)) return@editPrefs
            prefs[ENABLED_LAYOUT_IDS]?.let { stored ->
                val kept = stored.split(',')
                    .filter { it.isNotEmpty() && it != id }
                    .ifEmpty { listOf(BuiltInLayouts.DEFAULT_ID) }
                prefs[ENABLED_LAYOUT_IDS] = kept.joinToString(",")
            }
            if (prefs[ACTIVE_LAYOUT_ID] == id) {
                prefs[ACTIVE_LAYOUT_ID] = BuiltInLayouts.DEFAULT_ID
            }
        }

    suspend fun setThemeMode(mode: ThemeMode) =
        editPrefs { it[THEME_MODE] = mode.name }

    suspend fun setDynamicColor(value: Boolean) =
        editPrefs { it[DYNAMIC_COLOR] = value }

    suspend fun setKeyboardThemeId(id: String) =
        editPrefs { it[KEYBOARD_THEME_ID] = id }

    /**
     * Adds or removes one built-in from the keyboard Themes tool's shortlist.
     * The first toggle materialises the default set, so taking one theme off
     * the untouched shortlist doesn't suddenly mean "only that change".
     */
    suspend fun setThemesPanelBuiltIn(id: String, shown: Boolean) =
        editPrefs { prefs ->
            val current = prefs[THEMES_PANEL_BUILTINS] ?: DefaultThemesPanelBuiltIns
            prefs[THEMES_PANEL_BUILTINS] = if (shown) current + id else current - id
        }

    suspend fun setAutoThemeEnabled(value: Boolean) =
        editPrefs { it[AUTO_THEME_ENABLED] = value }

    suspend fun setAutoThemeLightId(id: String) =
        editPrefs { it[AUTO_THEME_LIGHT_ID] = id }

    suspend fun setAutoThemeDarkId(id: String) =
        editPrefs { it[AUTO_THEME_DARK_ID] = id }

    suspend fun setAutoThemeTrigger(value: AutoThemeTrigger) =
        editPrefs { it[AUTO_THEME_TRIGGER] = value.name }

    suspend fun setAutoThemeDayStart(minutes: Int) =
        editPrefs { it[AUTO_THEME_DAY_START] = minutes.coerceIn(0, 24 * 60 - 1) }

    suspend fun setAutoThemeNightStart(minutes: Int) =
        editPrefs { it[AUTO_THEME_NIGHT_START] = minutes.coerceIn(0, 24 * 60 - 1) }

    /**
     * Turns the random selection on or off for one half of the auto pair.
     *
     * Turning it on with an empty pool seeds the pool with the one theme that
     * half was already showing, and selects straight away, so the half is never
     * left in a state with nothing to show. Turning it off keeps the pool, so
     * coming back does not mean assembling the set again.
     */
    suspend fun setAutoThemeSlotRandom(darkSlot: Boolean, on: Boolean) =
        editPrefs { prefs ->
            prefs[if (darkSlot) AUTO_THEME_DARK_RANDOM else AUTO_THEME_LIGHT_RANDOM] = on
            if (!on) return@editPrefs
            val poolKey = if (darkSlot) AUTO_THEME_DARK_POOL else AUTO_THEME_LIGHT_POOL
            val fixedKey = if (darkSlot) AUTO_THEME_DARK_ID else AUTO_THEME_LIGHT_ID
            val pool = prefs[poolKey].orEmpty().ifEmpty {
                setOf(prefs[fixedKey] ?: DEFAULT_THEME_ID)
            }
            prefs[poolKey] = pool
            prefs.shuffleAutoThemeSlots(onlyEmpty = true)
        }

    /** Adds one theme to a random half's pool, or takes it out. */
    suspend fun setAutoThemePoolMember(darkSlot: Boolean, id: String, inPool: Boolean) =
        editPrefs { prefs ->
            val poolKey = if (darkSlot) AUTO_THEME_DARK_POOL else AUTO_THEME_LIGHT_POOL
            val current = prefs[poolKey].orEmpty()
            val next = if (inPool) current + id else current - id
            // The last theme cannot be taken out: a random half with an empty
            // pool has nothing to select, and the UI disables that checkbox.
            if (next.isEmpty()) return@editPrefs
            prefs[poolKey] = next
            // A selection that has just left the pool is replaced now rather
            // than at the next interval, so the board never shows a theme the
            // user has removed from the set.
            val shuffledKey =
                if (darkSlot) AUTO_THEME_SHUFFLE_DARK_ID else AUTO_THEME_SHUFFLE_LIGHT_ID
            if (prefs[shuffledKey].orEmpty() !in next) prefs[shuffledKey] = next.min()
        }

    suspend fun setAutoThemeShuffleInterval(value: RotationInterval) =
        editPrefs { it[AUTO_THEME_SHUFFLE_INTERVAL] = value.name }

    /** Selects a new theme for every random half of the pair, now. */
    suspend fun shuffleAutoThemeNow(
        nowEpochMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        random: Random = Random,
    ) = editPrefs { prefs ->
        prefs.shuffleAutoThemeSlots(
            onlyEmpty = false,
            nowEpochMs = nowEpochMs,
            nowElapsedMs = nowElapsedMs,
            random = random,
        )
    }

    /**
     * Writes the next theme for each random half, and stamps both clocks.
     *
     * [onlyEmpty] leaves a half that has already selected alone, which is what
     * turning the random selection on wants: it fills the blank without
     * disturbing the other half's schedule.
     */
    private fun MutablePreferences.shuffleAutoThemeSlots(
        onlyEmpty: Boolean,
        nowEpochMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        random: Random = Random,
    ) {
        var wrote = false
        for (darkSlot in listOf(false, true)) {
            val randomOn =
                this[if (darkSlot) AUTO_THEME_DARK_RANDOM else AUTO_THEME_LIGHT_RANDOM] ?: false
            if (!randomOn) continue
            val pool = this[if (darkSlot) AUTO_THEME_DARK_POOL else AUTO_THEME_LIGHT_POOL].orEmpty()
            if (pool.isEmpty()) continue
            val key = if (darkSlot) AUTO_THEME_SHUFFLE_DARK_ID else AUTO_THEME_SHUFFLE_LIGHT_ID
            val current = this[key].orEmpty()
            if (onlyEmpty && current.isNotBlank()) continue
            this[key] = nextShuffledId(pool, current, random)
            wrote = true
        }
        if (!wrote) return
        this[AUTO_THEME_SHUFFLED_AT] = nowEpochMs
        this[AUTO_THEME_SHUFFLED_AT_ELAPSED] = nowElapsedMs
    }

    /** Adds the theme or replaces the stored theme with the same id. */
    suspend fun upsertCustomTheme(theme: ThemeSpec) =
        editPrefs { prefs ->
            val current = prefs[CUSTOM_THEMES]?.let { ThemeCodec.decodeList(it) }.orEmpty()
            val next = current.filter { it.id != theme.id } + theme
            prefs[CUSTOM_THEMES] = ThemeCodec.encodeList(next)
        }

    /** Deletes a custom theme; falls back to the default theme if it was selected. */
    suspend fun deleteCustomTheme(id: String) =
        editPrefs { prefs ->
            val current = prefs[CUSTOM_THEMES]?.let { ThemeCodec.decodeList(it) }.orEmpty()
            prefs[CUSTOM_THEMES] = ThemeCodec.encodeList(current.filter { it.id != id })
            // A family takes every member's bookkeeping with it: selection and
            // the rotation entries can all point at a variant, not the parent.
            val removedIds = current.find { it.id == id }
                ?.selfAndVariants()?.map { it.id }
                ?: listOf(id)
            prefs.cleanupThemeIdRefs(removedIds, fallbackThemeId = DEFAULT_THEME_ID)
        }

    /**
     * Deletes one variant of a custom family. Selection falls back to the
     * family's parent rather than to the default theme: the user removed one
     * look, not the theme.
     */
    suspend fun deleteCustomThemeVariant(parentId: String, variantId: String) =
        editPrefs { prefs ->
            val current = prefs[CUSTOM_THEMES]?.let { ThemeCodec.decodeList(it) }.orEmpty()
            val parent = current.find { it.id == parentId } ?: return@editPrefs
            val next = parent.copy(variants = parent.variants.filter { it.id != variantId })
            prefs[CUSTOM_THEMES] = ThemeCodec.encodeList(current.filter { it.id != parentId } + next)
            prefs.cleanupThemeIdRefs(listOf(variantId), fallbackThemeId = parentId)
        }

    /**
     * The per-id bookkeeping a removed theme leaves behind, cleared in the
     * same write that removed it. Selection falls back to [fallbackThemeId];
     * the rotation entry goes so a later theme reusing an id cannot inherit a
     * stranger's photo. Image files are left for the sweep, which knows what
     * else refers to them.
     */
    private fun MutablePreferences.cleanupThemeIdRefs(
        ids: Collection<String>,
        fallbackThemeId: String,
    ) {
        val idSet = ids.toSet()
        if (this[KEYBOARD_THEME_ID] in idSet) this[KEYBOARD_THEME_ID] = fallbackThemeId
        if (this[AUTO_THEME_LIGHT_ID] in idSet) this[AUTO_THEME_LIGHT_ID] = fallbackThemeId
        if (this[AUTO_THEME_DARK_ID] in idSet) this[AUTO_THEME_DARK_ID] = fallbackThemeId
        cleanupAutoThemePool(idSet, AUTO_THEME_LIGHT_POOL, AUTO_THEME_SHUFFLE_LIGHT_ID)
        cleanupAutoThemePool(idSet, AUTO_THEME_DARK_POOL, AUTO_THEME_SHUFFLE_DARK_ID)
        this[PHOTO_ROTATION_STATE]?.let { stored ->
            val states = RotationStateCodec.decode(stored)
            if (states.keys.any { it in idSet }) {
                this[PHOTO_ROTATION_STATE] = RotationStateCodec.encode(states - idSet)
            }
        }
        this[PHOTO_ROTATE_SCOPE_THEMES]?.let { selected ->
            if (selected.any { it in idSet }) {
                this[PHOTO_ROTATE_SCOPE_THEMES] = selected - idSet
            }
        }
    }

    /**
     * Takes deleted ids out of one random half's pool and its stored selection.
     *
     * A pool emptied by the deletion is removed rather than left blank, so the
     * half falls back to the one theme it names and shows something instead of
     * nothing. The random flag is left on: the user's set has gone, but their
     * choice of how the half works has not.
     */
    private fun MutablePreferences.cleanupAutoThemePool(
        idSet: Set<String>,
        poolKey: Preferences.Key<Set<String>>,
        shuffledKey: Preferences.Key<String>,
    ) {
        val pool = this[poolKey] ?: return
        if (pool.none { it in idSet }) return
        val next = pool - idSet
        if (next.isEmpty()) {
            remove(poolKey)
            remove(shuffledKey)
            return
        }
        this[poolKey] = next
        if (this[shuffledKey].orEmpty() !in next) this[shuffledKey] = next.min()
    }

    // ---- Online photo backgrounds -------------------------------------

    /**
     * Puts a downloaded photo on a theme, with the credit that has to travel
     * with it, and hands back the file it replaced so the caller can delete it.
     *
     * Zeroing the board colour's alpha on the portrait slot is what the device
     * photo picker already does: the fresh photo shows at full strength, and
     * the user raises the alpha back to dim it. The landscape slot leaves the
     * alpha alone, because the portrait slot has already set the scrim.
     */
    suspend fun applyThemePhoto(
        themeId: String,
        path: String,
        credit: PhotoAttribution?,
        landscape: Boolean,
    ): String? {
        var replaced: String? = null
        editPrefs { prefs ->
            val photoAlpha = prefs[PHOTO_KEY_OPACITY] ?: PHOTO_KEY_ALPHA
            val current = prefs[CUSTOM_THEMES]?.let { ThemeCodec.decodeList(it) }.orEmpty()
            // The id can name a variant; the write goes back through the
            // family that carries it.
            val family = current.findThemeFamily(themeId) ?: return@editPrefs
            val theme = family.selfAndVariants().find { it.id == themeId } ?: return@editPrefs
            replaced = if (landscape) theme.backgroundImageLandscape else theme.backgroundImage
            val next = if (landscape) {
                theme.copy(backgroundImageLandscape = path, backgroundPhotoLandscape = credit)
            } else {
                theme.copy(
                    backgroundImage = path,
                    backgroundPhoto = credit,
                    boardBackground = theme.boardBackground and 0x00FFFFFFL,
                    // Opaque keys cover most of a keyboard, so a photo behind
                    // them is barely visible and choosing one feels like it did
                    // nothing. Only keys that are still fully opaque are
                    // changed, so a theme the user already tuned is left alone.
                    keyBackground = theme.keyBackground.softenedForPhoto(photoAlpha),
                    modifierKeyBackground =
                        theme.modifierKeyBackground.softenedForPhoto(photoAlpha),
                )
            }
            val nextFamily = family.replacingMember(themeId) { next }
            prefs[CUSTOM_THEMES] =
                ThemeCodec.encodeList(current.filter { it.id != family.id } + nextFamily)
        }
        return replaced?.takeIf { it != path }
    }

    /**
     * Takes a photo off a theme, restoring the board's opacity if applying one
     * had zeroed it — otherwise removing the image leaves a see-through board.
     */
    suspend fun clearThemePhoto(themeId: String, landscape: Boolean): String? {
        var removed: String? = null
        editPrefs { prefs ->
            val current = prefs[CUSTOM_THEMES]?.let { ThemeCodec.decodeList(it) }.orEmpty()
            val family = current.findThemeFamily(themeId) ?: return@editPrefs
            val theme = family.selfAndVariants().find { it.id == themeId } ?: return@editPrefs
            removed = if (landscape) theme.backgroundImageLandscape else theme.backgroundImage
            val next = if (landscape) {
                theme.copy(backgroundImageLandscape = null, backgroundPhotoLandscape = null)
            } else {
                theme.copy(
                    backgroundImage = null,
                    backgroundPhoto = null,
                    boardBackground = if ((theme.boardBackground ushr 24) == 0L) {
                        theme.boardBackground or 0xFF000000L
                    } else {
                        theme.boardBackground
                    },
                )
            }
            val nextFamily = family.replacingMember(themeId) { next }
            prefs[CUSTOM_THEMES] =
                ThemeCodec.encodeList(current.filter { it.id != family.id } + nextFamily)
        }
        return removed
    }

    /**
     * A new key gets a clean slate.
     *
     * The request budget and the page cache both belong to the key that filled
     * them, so somebody pasting a fresh key after hitting a limit would
     * otherwise keep being told there are no requests left.
     */
    suspend fun setUnsplashApiKey(value: String) =
        editPrefs { it[PHOTO_UNSPLASH_KEY] = value.trim() }

    suspend fun setPexelsApiKey(value: String) =
        editPrefs { it[PHOTO_PEXELS_KEY] = value.trim() }

    suspend fun setPhotoRotateEnabled(value: Boolean) =
        editPrefs { it[PHOTO_ROTATE_ENABLED] = value }

    suspend fun setPhotoRotateInterval(value: RotationInterval) =
        editPrefs { it[PHOTO_ROTATE_INTERVAL] = value.name }

    suspend fun setPhotoRotateScope(value: RotationScope) =
        editPrefs { it[PHOTO_ROTATE_SCOPE] = value.name }

    suspend fun setPhotoRotateScopeThemes(ids: Set<String>) =
        editPrefs { it[PHOTO_ROTATE_SCOPE_THEMES] = ids }

    suspend fun setPhotoRotateSources(sources: Set<RotationSourceKind>) =
        editPrefs { it[PHOTO_ROTATE_SOURCES] = sources.mapTo(mutableSetOf()) { kind -> kind.name } }

    suspend fun setPhotoRotateTopics(slugs: List<String>) =
        editPrefs { it[PHOTO_ROTATE_TOPICS] = slugs.joinToString("\t") }

    suspend fun setPhotoRotateQueries(queries: List<String>) =
        editPrefs { prefs ->
            prefs[PHOTO_ROTATE_QUERIES] = queries
                .map { it.replace('\t', ' ').trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\t")
        }

    suspend fun setPhotoLandscapeOnly(value: Boolean) =
        editPrefs { it[PHOTO_LANDSCAPE_ONLY] = value }

    suspend fun setPhotoSafeSearch(value: Boolean) =
        editPrefs { it[PHOTO_SAFE_SEARCH] = value }

    suspend fun setPhotoFetchOnMetered(value: Boolean) =
        editPrefs { it[PHOTO_FETCH_ON_METERED] = value }

    suspend fun setPhotoPoolTarget(value: Int) =
        editPrefs {
            it[PHOTO_POOL_TARGET] = value.coerceIn(
                PhotoBackgroundSettings.MIN_POOL_TARGET,
                PhotoBackgroundSettings.MAX_POOL_TARGET,
            )
        }

    suspend fun setPhotoPoolBudgetMb(value: Int) = editPrefs {
        it[PHOTO_POOL_BUDGET_MB] = value.coerceIn(
            PhotoBackgroundSettings.POOL_BUDGET_MB_RANGE.first,
            PhotoBackgroundSettings.POOL_BUDGET_MB_RANGE.last,
        )
    }

    suspend fun setPhotoKeyOpacity(value: Float) =
        editPrefs { it[PHOTO_KEY_OPACITY] = value.coerceIn(0.2f, 1f) }

    suspend fun setPhotoSeedPalette(value: Boolean) =
        editPrefs { it[PHOTO_SEED_PALETTE] = value }

    suspend fun setPhotoReadabilityGuard(value: Boolean) =
        editPrefs { it[PHOTO_READABILITY_GUARD] = value }

    /**
     * The settings app's fingerprint lock; see [AppLockSettings] for what it
     * does and does not protect.
     *
     * Its own flow, like [photoRotationStates] below, so it costs the
     * [KeyboardSettings] data class none of its last argument slot.
     *
     * The locked branch reads the direct-boot mirror rather than publishing
     * defaults the way [photoRotationStates] does: an empty [AppLockSettings]
     * has `enabled = false`, so a locked session would answer "no lock" for
     * every screen. The settings app cannot run on a lock screen at all today,
     * which makes that unreachable rather than merely unlikely, but a
     * fail-open written into a security feature on the strength of somewhere
     * else's invariant is the kind of thing that stops being true quietly.
     */
    /**
     * Whether other apps may control the keyboard, and what they may do; see
     * [AutomationSettings]. Its own flow rather than a [KeyboardSettings] field:
     * the automation receiver is its main reader.
     *
     * Locked, it answers "off" rather than reading the direct-boot mirror,
     * which does not carry these keys. The receiver cannot run before the
     * first unlock anyway, and failing closed is the right way to be wrong.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val automation: Flow<AutomationSettings> = unlocked
        .flatMapLatest { isUnlocked ->
            if (isUnlocked) {
                context.dataStore.data.map { p ->
                    AutomationSettings(
                        enabled = p[AUTOMATION_ENABLED] ?: false,
                        allowed = AutomationPermission.entries.filterTo(LinkedHashSet()) {
                            p[automationKey(it)] ?: it.defaultAllowed
                        },
                    )
                }
            } else {
                flowOf(AutomationSettings())
            }
        }
        .distinctUntilChanged()

    suspend fun setAutomationEnabled(value: Boolean) =
        editPrefs { it[AUTOMATION_ENABLED] = value }

    suspend fun setAutomationAllowed(permission: AutomationPermission, value: Boolean) =
        editPrefs { it[automationKey(permission)] = value }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val appLock: Flow<AppLockSettings> = unlocked
        .flatMapLatest { isUnlocked ->
            if (isUnlocked) context.dataStore.data else locked.snapshots()
        }
        .map { p ->
            AppLockSettings(
                enabled = p[APP_LOCK_ENABLED] ?: AppLockDefaults.enabled,
                lockedTargets = p[APP_LOCK_TARGETS] ?: AppLockDefaults.lockedTargets,
                // An unreadable value falls back rather than throwing: this
                // enum can lose a member across versions, and a restore from a
                // newer build must not crash the screen that would fix it.
                relock = p[APP_LOCK_RELOCK]
                    ?.let { name -> AppLockRelock.entries.find { it.name == name } }
                    ?: AppLockDefaults.relock,
                allowDeviceCredential = p[APP_LOCK_ALLOW_CREDENTIAL]
                    ?: AppLockDefaults.allowDeviceCredential,
            )
        }
        .distinctUntilChanged()

    suspend fun setAppLockEnabled(value: Boolean) =
        editPrefs { it[APP_LOCK_ENABLED] = value }

    suspend fun setAppLockTargets(value: Set<String>) =
        editPrefs { it[APP_LOCK_TARGETS] = value }

    /**
     * Ticks or unticks one lockable thing.
     *
     * Reads the stored set rather than taking the caller's copy so two rows
     * toggled in the same frame cannot overwrite each other.
     */
    suspend fun setAppLockTarget(id: String, locked: Boolean) =
        editPrefs { prefs ->
            val current = prefs[APP_LOCK_TARGETS].orEmpty()
            prefs[APP_LOCK_TARGETS] = if (locked) current + id else current - id
        }

    suspend fun setAppLockRelock(value: AppLockRelock) =
        editPrefs { it[APP_LOCK_RELOCK] = value.name }

    suspend fun setAppLockAllowDeviceCredential(value: Boolean) =
        editPrefs { it[APP_LOCK_ALLOW_CREDENTIAL] = value }

    /**
     * Which photo each rotating theme is showing.
     *
     * Its own flow rather than a field on [KeyboardSettings]: a rotation would
     * otherwise re-emit every setting in the app, and this is the one piece of
     * theme state the user did not author, so losing it costs nothing they made.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val photoRotationStates: Flow<Map<String, RotationState>> = unlocked
        .flatMapLatest { isUnlocked ->
            // Locked, the photos live in credential-encrypted storage and
            // cannot be read at all, so there is nothing to publish.
            if (isUnlocked) context.dataStore.data else flowOf(emptyPreferences())
        }
        .map { p -> p[PHOTO_ROTATION_STATE]?.let { RotationStateCodec.decode(it) }.orEmpty() }
        .distinctUntilChanged()

    /** Records the photo a theme has moved on to. */
    suspend fun setRotationState(themeId: String, state: RotationState) =
        editPrefs { prefs ->
            val current = prefs[PHOTO_ROTATION_STATE]?.let { RotationStateCodec.decode(it) }.orEmpty()
            prefs[PHOTO_ROTATION_STATE] = RotationStateCodec.encode(current + (themeId to state))
        }

    /** Drops entries for themes that no longer exist. */
    suspend fun pruneRotationStates(liveThemeIds: Set<String>) =
        editPrefs { prefs ->
            val current = prefs[PHOTO_ROTATION_STATE]?.let { RotationStateCodec.decode(it) }.orEmpty()
            val kept = current.filterKeys { it in liveThemeIds }
            if (kept.size != current.size) {
                prefs[PHOTO_ROTATION_STATE] = RotationStateCodec.encode(kept)
            }
        }

    /**
     * The user's own panel layouts (issue #63), unrepaired, for the editor: a
     * panel not in the list is on its shipped grid. The two settings these
     * replaced are folded in on read — see [foldLegacyPanelPrefs].
     *
     * Its own flow, like [photoRotationStates]: a panel layout is a few
     * hundred keys the rest of the app never reads, and [KeyboardSettings] is
     * near its argument ceiling.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val customPanelLayouts: Flow<List<PanelLayoutSpec>> = unlocked
        .flatMapLatest { isUnlocked ->
            if (isUnlocked) context.dataStore.data else locked.snapshots()
        }
        .map { p -> readPanelLayouts(p) }
        .distinctUntilChanged()

    /**
     * What the keyboard draws: every panel, resolved against the shipped grid
     * and repaired, so a hand-edited layout can never fail to draw.
     */
    val panelLayouts: Flow<Map<PanelKind, PanelLayoutSpec>> = customPanelLayouts
        .map { custom -> resolvePanelLayouts(custom).mapValues { (_, spec) -> spec.repair().spec } }
        .distinctUntilChanged()

    private fun readPanelLayouts(p: Preferences): List<PanelLayoutSpec> = foldLegacyPanelPrefs(
        stored = p[PANEL_LAYOUTS]?.let(PanelLayoutCodec::decodeList).orEmpty(),
        legacyTextEdit = TextEditLayoutCodec.decode(p[TEXT_EDIT_LAYOUT]),
        legacyClipboardBottomRow = p[CLIPBOARD_BOTTOM_ROW],
    )

    /**
     * Rewrites one panel's layout inside a single edit, starting from what the
     * keyboard would draw now (the user's, or the shipped grid), so the first
     * edit of a shipped panel starts from that panel rather than from nothing.
     */
    suspend fun updatePanelLayout(kind: PanelKind, transform: (PanelLayoutSpec) -> PanelLayoutSpec) =
        editPrefs { prefs ->
            val current = readPanelLayouts(prefs)
            val next = transform(resolvePanelLayout(kind, current)).copy(panel = kind)
            writePanelLayouts(prefs, current.filter { it.panel != kind } + next, kind)
        }

    /** Stores a whole panel layout; the raw-JSON editor and undo go through here. */
    suspend fun upsertPanelLayout(spec: PanelLayoutSpec) =
        editPrefs { prefs ->
            val current = readPanelLayouts(prefs)
            writePanelLayouts(prefs, current.filter { it.panel != spec.panel } + spec, spec.panel)
        }

    /** Back to the shipped grid for [kind]; the editor's Reset. */
    suspend fun resetPanelLayout(kind: PanelKind) =
        editPrefs { prefs ->
            val current = readPanelLayouts(prefs)
            writePanelLayouts(prefs, current.filter { it.panel != kind }, kind)
        }

    /**
     * Writes the list and clears the legacy key the written panel replaced, so
     * the read-time fold cannot resurrect it — a Reset must land on the shipped
     * grid, not on the old bottom-row flag (cf. [setClipboardCopiedCodeChip]).
     */
    private fun writePanelLayouts(prefs: MutablePreferences, layouts: List<PanelLayoutSpec>, kind: PanelKind) {
        if (layouts.isEmpty()) prefs.remove(PANEL_LAYOUTS) else prefs[PANEL_LAYOUTS] = PanelLayoutCodec.encodeList(layouts)
        when (kind) {
            PanelKind.TEXT_EDIT -> prefs.remove(TEXT_EDIT_LAYOUT)
            PanelKind.CLIPBOARD -> prefs.remove(CLIPBOARD_BOTTOM_ROW)
            PanelKind.EMOJI, PanelKind.TRACKPAD, PanelKind.NUMPAD -> Unit
        }
    }

    suspend fun setKeyHeightDp(value: Int) =
        editPrefs { it[KEY_HEIGHT] = value.coerceIn(KEY_HEIGHT_MIN_DP, KEY_HEIGHT_MAX_DP) }

    suspend fun setNumberRowHeightDp(value: Int) =
        editPrefs { it[NUMBER_ROW_HEIGHT] = value.coerceIn(KEY_HEIGHT_MIN_DP, KEY_HEIGHT_MAX_DP) }

    suspend fun setSplitKeyboard(value: Boolean) =
        editPrefs { it[SPLIT_KEYBOARD] = value }

    suspend fun setSplitGapPercent(value: Int) =
        editPrefs { it[SPLIT_GAP_PERCENT] = value.coerceIn(5, 40) }

    suspend fun setFloatingKeyboard(value: Boolean) =
        editPrefs { it[FLOATING_KEYBOARD] = value }

    suspend fun setPersistentKeyboard(value: Boolean) =
        editPrefs { it[PERSISTENT_KEYBOARD] = value }

    suspend fun setFloatingWidthDp(value: Int) =
        editPrefs { it[FLOATING_WIDTH] = value.coerceIn(240, 500) }

    /**
     * Height on its own, for the settings slider. The resize grip writes both
     * axes at once through [setFloatingSize]; this exists because height was
     * drag-only, so a bad drag had no way back and no way to be typed exactly.
     */
    suspend fun setFloatingHeightScale(value: Float) =
        editPrefs { it[FLOATING_HEIGHT_SCALE] = value.coerceIn(0.6f, 1.6f) }

    /** Both axes from one resize-grip gesture, persisted in a single edit. */
    suspend fun setFloatingSize(widthDp: Int, heightScale: Float) =
        editPrefs {
            it[FLOATING_WIDTH] = widthDp.coerceIn(240, 500)
            it[FLOATING_HEIGHT_SCALE] = heightScale.coerceIn(0.6f, 1.6f)
        }

    suspend fun setFloatingPosition(x: Float, y: Float) =
        editPrefs {
            it[FLOATING_X] = x.coerceIn(0f, 1f)
            it[FLOATING_Y] = y.coerceIn(0f, 1f)
        }

    // ---- per-variant sizing ----
    //
    // Writing to PORTRAIT edits the base values (the plain settings), so the
    // variant editor and the ordinary sliders drive the same preferences
    // instead of shadowing each other. A null value clears the override and
    // lets the variant inherit portrait again.

    suspend fun setVariantKeyHeightDp(variant: ScreenVariant, value: Int?) =
        editVariant(variant, KEY_HEIGHT, keyHeightKey(variant), value?.coerceIn(32, 100))

    suspend fun setVariantNumberRowHeightDp(variant: ScreenVariant, value: Int?) =
        editVariant(
            variant, NUMBER_ROW_HEIGHT, numberRowHeightKey(variant), value?.coerceIn(32, 100),
        )

    suspend fun setVariantBottomPaddingDp(variant: ScreenVariant, value: Int?) =
        editVariant(
            variant, BOTTOM_PADDING, bottomPaddingKey(variant),
            value?.coerceIn(0, MAX_BOTTOM_PADDING_DP),
        )

    suspend fun setVariantWidthPercent(variant: ScreenVariant, value: Int?) =
        editVariant(
            variant, KEYBOARD_WIDTH_PERCENT, widthPercentKey(variant), value?.coerceIn(50, 100),
        )

    suspend fun setVariantFontScale(variant: ScreenVariant, value: Float?) =
        editVariant(variant, FONT_SCALE, fontScaleKey(variant), value?.coerceIn(KeyFontScaleRange))

    suspend fun setVariantAlignment(variant: ScreenVariant, value: KeyboardAlignment?) =
        editVariant(variant, KEYBOARD_ALIGNMENT, alignmentKey(variant), value?.name)

    /**
     * Whole-keyboard size multiplier for [variant] (folded vs unfolded etc.).
     * There is no base/portrait key — portrait is sized by its plain key-height
     * slider — so this only ever writes the per-variant override key.
     */
    suspend fun setVariantKeyboardScale(variant: ScreenVariant, value: Float?) {
        if (!variant.isOverride) return
        editPrefs {
            val v = value?.coerceIn(0.5f, 1.5f)
            if (v == null) it.remove(keyboardScaleKey(variant)) else it[keyboardScaleKey(variant)] = v
        }
    }

    /**
     * One inline-resize commit: only the supplied fields, in a single edit.
     *
     * A null field means "the user did not change this" and writes nothing —
     * deliberately, not as a convenience: an untouched key height must leave
     * [KEY_HEIGHT] absent so `keyHeightUntouched` keeps steering the tablet
     * defaults. Never clears an existing override (pass through
     * [setVariantKeyHeightDp] and friends to do that).
     *
     * The edge pads (issue #82) follow the same portrait-is-base rule as the
     * heights so the tool and the Layout sliders read and write one number:
     * portrait lands on [SIDE_PAD_LEFT_SCALE] / [SIDE_PAD_RIGHT_SCALE], every
     * other variant on its own override key.
     */
    suspend fun setVariantSizing(
        variant: ScreenVariant,
        keyHeightDp: Int?,
        numberRowHeightDp: Int?,
        bottomPaddingDp: Int?,
        sidePadLeftScale: Float? = null,
        sidePadRightScale: Float? = null,
    ) = editPrefs { prefs ->
        sidePadLeftScale?.let {
            val key = if (variant.isOverride) sidePadLeftScaleKey(variant) else SIDE_PAD_LEFT_SCALE
            prefs[key] = it.coerceIn(SidePadScaleRange.start, SidePadScaleRange.endInclusive)
        }
        sidePadRightScale?.let {
            val key = if (variant.isOverride) sidePadRightScaleKey(variant) else SIDE_PAD_RIGHT_SCALE
            prefs[key] = it.coerceIn(SidePadScaleRange.start, SidePadScaleRange.endInclusive)
        }
        keyHeightDp?.let {
            val key = if (variant.isOverride) keyHeightKey(variant) else KEY_HEIGHT
            prefs[key] = it.coerceIn(KEY_HEIGHT_MIN_DP, KEY_HEIGHT_MAX_DP)
        }
        numberRowHeightDp?.let {
            val key = if (variant.isOverride) numberRowHeightKey(variant) else NUMBER_ROW_HEIGHT
            prefs[key] = it.coerceIn(KEY_HEIGHT_MIN_DP, KEY_HEIGHT_MAX_DP)
        }
        bottomPaddingDp?.let {
            val key = if (variant.isOverride) bottomPaddingKey(variant) else BOTTOM_PADDING
            prefs[key] = it.coerceIn(0, MAX_BOTTOM_PADDING_DP)
        }
    }

    /** Clears every override on [variant], returning it to the portrait values. */
    suspend fun clearVariantSizing(variant: ScreenVariant) {
        if (!variant.isOverride) return
        editPrefs {
            it.remove(keyHeightKey(variant))
            it.remove(numberRowHeightKey(variant))
            it.remove(bottomPaddingKey(variant))
            it.remove(widthPercentKey(variant))
            it.remove(fontScaleKey(variant))
            it.remove(alignmentKey(variant))
            it.remove(keyboardScaleKey(variant))
            it.remove(keyGapScaleKey(variant))
            it.remove(sidePadScaleKey(variant))
            it.remove(sidePadLeftScaleKey(variant))
            it.remove(sidePadRightScaleKey(variant))
            it.remove(bottomRowHeightKey(variant))
            it.remove(variantNumberRowKey(variant))
        }
    }

    /**
     * The four later per-variant overrides. Each writes only the override key,
     * never the base one: unlike key height, none of these has a "portrait is
     * the base value" story to preserve, so a null simply clears the override.
     */
    suspend fun setVariantKeyGapScale(variant: ScreenVariant, value: Float?) =
        editVariantOnly(variant, keyGapScaleKey(variant), value?.coerceIn(0f, 2f))

    suspend fun setVariantSidePadLeftScale(variant: ScreenVariant, value: Float?) =
        editVariantOnly(
            variant,
            sidePadLeftScaleKey(variant),
            value?.coerceIn(SidePadScaleRange.start, SidePadScaleRange.endInclusive),
        )

    suspend fun setVariantSidePadRightScale(variant: ScreenVariant, value: Float?) =
        editVariantOnly(
            variant,
            sidePadRightScaleKey(variant),
            value?.coerceIn(SidePadScaleRange.start, SidePadScaleRange.endInclusive),
        )

    suspend fun setVariantBottomRowHeightDp(variant: ScreenVariant, value: Int?) =
        editVariantOnly(variant, bottomRowHeightKey(variant), value?.coerceIn(0, 100))

    suspend fun setVariantNumberRow(variant: ScreenVariant, value: Boolean?) =
        editVariantOnly(variant, variantNumberRowKey(variant), value)

    private suspend fun <T : Any> editVariantOnly(
        variant: ScreenVariant,
        key: Preferences.Key<T>,
        value: T?,
    ) {
        if (!variant.isOverride) return
        editPrefs { if (value == null) it.remove(key) else it[key] = value }
    }

    private suspend fun <T : Any> editVariant(
        variant: ScreenVariant,
        baseKey: Preferences.Key<T>,
        overrideKey: Preferences.Key<T>,
        value: T?,
    ) = editPrefs { prefs ->
        val key = if (variant.isOverride) overrideKey else baseKey
        if (value == null) prefs.remove(key) else prefs[key] = value
    }

    suspend fun setKeyboardWidthPercent(value: Int) =
        editPrefs { it[KEYBOARD_WIDTH_PERCENT] = value.coerceIn(50, 100) }

    suspend fun setKeyboardAlignment(value: KeyboardAlignment) =
        editPrefs { it[KEYBOARD_ALIGNMENT] = value.name }

    suspend fun setBottomPaddingDp(value: Int) =
        editPrefs { it[BOTTOM_PADDING] = value.coerceIn(0, MAX_BOTTOM_PADDING_DP) }

    /** Hands bottom padding back to the automatic amount, [autoBottomPaddingDp]. */
    suspend fun resetBottomPaddingDp() = editPrefs { it.remove(BOTTOM_PADDING) }

    suspend fun setKeyCornerRadiusDp(value: Int) =
        editPrefs { it[KEY_CORNER_RADIUS] = value.coerceIn(0, 28) }

    suspend fun setKeyGapScale(value: Float) =
        editPrefs { it[KEY_GAP_SCALE] = value.coerceIn(0f, 2f) }

    suspend fun setFontScale(value: Float) =
        editPrefs { it[FONT_SCALE] = value.coerceIn(KeyFontScaleRange) }

    suspend fun setKeyFontId(value: String) =
        editPrefs { it[KEY_FONT_ID] = value }

    /** Records both the imported file's display name and selects it. */
    suspend fun setCustomFont(name: String) =
        editPrefs {
            it[CUSTOM_FONT_NAME] = name
            it[KEY_FONT_ID] = "custom"
        }

    /**
     * Selects [fontId] for [script] (a [com.wasimaster.wmkeyboard.core.script.ScriptId]
     * name). "default" drops the entry so the script falls back to its automatic
     * Noto face and the map stays compact.
     */
    suspend fun setScriptFontId(script: String, fontId: String) =
        editPrefs { prefs -> putScriptFontId(prefs, script, fontId) }

    /** Records an imported font's display name for [script] and selects it. */
    suspend fun setCustomScriptFont(script: String, customFontId: String, name: String) =
        editPrefs { prefs ->
            val current = prefs[CUSTOM_SCRIPT_FONT_NAMES]?.let { decodeScriptFontIds(it) }.orEmpty()
            prefs[CUSTOM_SCRIPT_FONT_NAMES] = encodeScriptFontIds(current + (script to name))
            putScriptFontId(prefs, script, customFontId)
        }

    /**
     * The preview-bubble block. Split out because the height is stored twice —
     * once per bubble style — and picking between the two keys needs `onKey`
     * resolved first; see [KeyPopupSettings.heightDp].
     */
    private fun popupFromPrefs(p: Preferences, defaults: KeyboardSettings): KeyPopupSettings {
        val onKey = p[KEY_POPUP_ON_KEY] ?: defaults.popup.onKey
        val onKeyHeightDp = p[KEY_POPUP_HEIGHT] ?: defaults.popup.onKeyHeightDp
        val floatingHeightDp = p[KEY_POPUP_FLOATING_HEIGHT] ?: defaults.popup.floatingHeightDp
        return KeyPopupSettings(
            enabled = p[KEY_POPUP] ?: defaults.popup.enabled,
            minDurationMs = p[KEY_POPUP_MIN_DURATION] ?: defaults.popup.minDurationMs,
            maxDurationMs = p[KEY_POPUP_MAX_DURATION] ?: defaults.popup.maxDurationMs,
            onKey = onKey,
            inNumericFields = p[KEY_POPUP_IN_NUMERIC] ?: defaults.popup.inNumericFields,
            fontScale = p[POPUP_FONT_SCALE] ?: defaults.popup.fontScale,
            heightDp = if (onKey) onKeyHeightDp else floatingHeightDp,
            onKeyHeightDp = onKeyHeightDp,
            floatingHeightDp = floatingHeightDp,
            floatingOffsetYDp = p[KEY_POPUP_OFFSET_Y] ?: defaults.popup.floatingOffsetYDp,
            floatingOffsetXDp = p[KEY_POPUP_OFFSET_X] ?: defaults.popup.floatingOffsetXDp,
            backgroundColor = p[KEY_POPUP_BACKGROUND] ?: defaults.popup.backgroundColor,
            textColor = p[KEY_POPUP_TEXT_COLOR] ?: defaults.popup.textColor,
            cornerRadiusDp = p[KEY_POPUP_RADIUS] ?: defaults.popup.cornerRadiusDp,
            shape = p[KEY_POPUP_SHAPE]
                ?.let { runCatching { KeyShapeKind.valueOf(it) }.getOrNull() }
                ?: defaults.popup.shape,
            // Falls back to the bubble's scale, which is the key the alternates
            // used to read: a board that had already sized them up keeps that
            // size, and only diverges once its own slider is touched.
            alternatesFontScale = p[ALTERNATES_FONT_SCALE]
                ?: p[POPUP_FONT_SCALE]
                ?: defaults.popup.alternatesFontScale,
            alternatesPaddingDp = p[ALTERNATES_PADDING] ?: defaults.popup.alternatesPaddingDp,
            alternatesColumns = p[ALTERNATES_COLUMNS] ?: defaults.popup.alternatesColumns,
            alternatesNearestFirst = p[ALTERNATES_NEAREST_FIRST]
                ?: defaults.popup.alternatesNearestFirst,
            alternatesHoldToSelect = p[ALTERNATES_HOLD_TO_SELECT]
                ?: defaults.popup.alternatesHoldToSelect,
        )
    }

    private fun putScriptFontId(prefs: MutablePreferences, script: String, fontId: String) {
        val current = prefs[SCRIPT_FONT_IDS]?.let { decodeScriptFontIds(it) }.orEmpty()
        val next = if (fontId == DEFAULT_FONT_ID) current - script else current + (script to fontId)
        // Writing the map is also what retires the old Bengali-only keys: once
        // there is a per-script entry, the migration below stops consulting them.
        if (next != current) prefs[SCRIPT_FONT_IDS] = encodeScriptFontIds(next)
    }

    /**
     * The per-script font map, with Bengali's old standalone choice folded in.
     *
     * The migration is a read-time fallback rather than a rewrite, so a
     * downgrade to a build that still reads `bengali_font_id` finds it untouched.
     * An explicit per-script entry always wins, which is what makes the fold-in
     * stop once the user picks anything on the new screen.
     */
    private fun scriptFontIdsFromPrefs(p: Preferences, defaults: KeyboardSettings): Map<String, String> {
        val stored = p[SCRIPT_FONT_IDS]?.let { decodeScriptFontIds(it) } ?: defaults.scriptFontIds
        if (stored.containsKey(BENGALI_SCRIPT)) return stored
        val legacy = p[BENGALI_FONT_ID]?.takeIf { it != DEFAULT_FONT_ID } ?: return stored
        return stored + (BENGALI_SCRIPT to legacy)
    }

    /** One stored policy name, or [fallback] when it is missing or unreadable. */
    private fun Preferences.policy(
        key: Preferences.Key<String>,
        fallback: MeteredPolicy,
    ): MeteredPolicy = this[key]
        ?.let { runCatching { MeteredPolicy.valueOf(it) }.getOrNull() }
        ?: fallback

    /**
     * Data saving, with the two switches it replaced folded in.
     *
     * `confirm_metered_downloads` and `ai_download_unmetered_only` said exactly
     * what [MeteredPolicy] says, one feature at a time, so they migrate to
     * [DataSaverSettings.downloads] rather than being dropped: Wi-Fi-only wins
     * (it was the stricter of the two), a switched-off confirmation means the
     * user has already said they do not want to be asked, and anything else
     * lands on the default. Read-time, like the Bengali font fold-in above, so
     * nothing is rewritten and a downgrade finds its old keys intact.
     */
    private fun dataSaverFromPrefs(p: Preferences, defaults: KeyboardSettings): DataSaverSettings {
        val d = defaults.dataSaver
        val legacyDownloads = when {
            p[AI_DOWNLOAD_UNMETERED] == true -> MeteredPolicy.BLOCK
            p[CONFIRM_METERED_DOWNLOADS] == false -> MeteredPolicy.ALLOW
            else -> d.downloads
        }
        return DataSaverSettings(
            manual = p[DS_MANUAL] ?: d.manual,
            trigger = p[DS_TRIGGER]
                ?.let { runCatching { DataSaverTrigger.valueOf(it) }.getOrNull() }
                ?: d.trigger,
            linkPreviews = p.policy(DS_LINK_PREVIEWS, d.linkPreviews),
            dictionaryLookup = p.policy(DS_DICTIONARY_LOOKUP, d.dictionaryLookup),
            photoBackgrounds = p.policy(DS_PHOTO_BACKGROUNDS, d.photoBackgrounds),
            weatherChip = p.policy(DS_WEATHER_CHIP, d.weatherChip),
            vocabAudio = p.policy(DS_VOCAB_AUDIO, d.vocabAudio),
            currencyRates = p.policy(DS_CURRENCY_RATES, d.currencyRates),
            addonRefresh = p.policy(DS_ADDON_REFRESH, d.addonRefresh),
            mediaSearch = p.policy(DS_MEDIA_SEARCH, d.mediaSearch),
            webSearch = p.policy(DS_WEB_SEARCH, d.webSearch),
            animatedEmoji = p.policy(DS_ANIMATED_EMOJI, d.animatedEmoji),
            downloads = p.policy(DS_DOWNLOADS, legacyDownloads),
            cloudAi = p.policy(DS_CLOUD_AI, d.cloudAi),
            cloudVoice = p.policy(DS_CLOUD_VOICE, d.cloudVoice),
        )
    }

    private fun customScriptFontNamesFromPrefs(
        p: Preferences,
        defaults: KeyboardSettings,
    ): Map<String, String> {
        val stored = p[CUSTOM_SCRIPT_FONT_NAMES]?.let { decodeScriptFontIds(it) }
            ?: defaults.customScriptFontNames
        if (stored.containsKey(BENGALI_SCRIPT)) return stored
        val legacy = p[CUSTOM_BENGALI_FONT_NAME]?.takeIf { it.isNotEmpty() } ?: return stored
        return stored + (BENGALI_SCRIPT to legacy)
    }

    /** Signals the IME that the learned-words file changed on disk. */
    // ---- backup ----

    /** Serializes every stored preference; see [SettingsBackup]. */
    suspend fun exportSettings(
        includeSecrets: Boolean,
        appVersion: Int,
        appVersionName: String,
    ): String = SettingsBackup.encode(
        context.dataStore.data.first(),
        includeSecrets,
        appVersion,
        appVersionName,
    )

    sealed interface ImportResult {
        data class Applied(val settings: Int, val skipped: Int) : ImportResult
        /** The file parsed but left the app unable to read its own settings. */
        data object RolledBack : ImportResult
        data object NotABackup : ImportResult
    }

    /**
     * Merges a backup into the current settings: keys named in the file are
     * overwritten, everything else is left alone, so an old backup never
     * resets settings that did not exist when it was made.
     *
     * A hand-edited file can carry a value of the wrong type for its key
     * (a string where an Int is expected), which DataStore stores happily
     * and then throws on at read time — bricking the settings screen and
     * the keyboard with it. So the whole write is verified by reading the
     * settings back, and rolled back to the previous snapshot if that
     * fails.
     */
    suspend fun importSettings(text: String): ImportResult {
        val parsed = SettingsBackup.decode(text) ?: return ImportResult.NotABackup
        val snapshot = context.dataStore.data.first()
        editPrefs { prefs -> parsed.entries.forEach { prefs.put(it) } }
        val readable = runCancellable { settings.first() }.isSuccess
        if (!readable) {
            editPrefs { prefs ->
                prefs.clear()
                for ((key, value) in snapshot.asMap()) {
                    @Suppress("UNCHECKED_CAST")
                    prefs[key as Preferences.Key<Any>] = value
                }
            }
            return ImportResult.RolledBack
        }
        return ImportResult.Applied(parsed.entries.size, parsed.skipped)
    }

    // ---- full-config bundle ----
    //
    // One file that can carry several independent parts of the app — settings,
    // custom themes, the learned dictionary, clipboard history, snippets — each
    // an opt-in section. See [ConfigBackup] for the container format.
    //
    // The file-backed stores (dictionary/clipboard/snippets) live under
    // filesDir as JSON the store itself wrote; export embeds that JSON verbatim
    // and import writes it straight back, so this repository never has to model
    // their internals. Custom themes are the one DataStore string preference
    // that gets its own section, so the settings section always excludes it.

    private val bundleJson = Json { ignoreUnknownKeys = true }

    /** Clip kinds worth exporting: the ones whose bytes/URIs survive the move. */
    private val TEXTUAL_CLIP_KINDS = setOf("TEXT", "HTML", "LINK")

    /** Key the rank adjustments file is nested under in the dictionary section. */
    private val WORD_RANKS_KEY = "wordRanks"

    /** The learned corrections and the tap model ride inside the dictionary
     * section the same way, and for the same reasons, as the rank adjustments. */
    private val LEARNED_CORRECTIONS_KEY = "learnedCorrections"
    private val TAP_MODEL_KEY = "tapOffsets"

    /**
     * The three files of the swipe-style section, under the keys they take
     * inside it. Named rather than pathed so the section survives a file being
     * renamed or moved, and so a bundle written by a build that had one more of
     * them still restores the ones this build knows.
     */
    private val SWIPE_SECTION_KEYS: Map<String, String> = linkedMapOf(
        "hand" to HAND_MODEL_FILE,
        "outcomes" to GLIDE_OUTCOMES_FILE,
        "shapes" to GLIDE_SHAPES_FILE,
    )

    /** The key the learned shapes take inside the swipe-style section. */
    private val SWIPE_SHAPES_KEY = "shapes"

    private fun storeFile(relativePath: String) = File(context.filesDir, relativePath)

    /** A store's JSON file as an element, or null when it's missing or empty. */
    private fun readStore(relativePath: String): JsonElement? {
        val file = storeFile(relativePath)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { bundleJson.parseToJsonElement(text) }.getOrNull()
    }

    /** Overwrites a store's JSON file with [element]; false on any I/O error. */
    private fun writeStore(relativePath: String, element: JsonElement): Boolean = runCatching {
        val file = storeFile(relativePath)
        file.parentFile?.mkdirs()
        file.writeText(element.toString())
        true
    }.getOrDefault(false)

    /**
     * Drops image/file/folder/video clips from a clipboard snapshot: those
     * point at files or content URIs that only exist on the source device, so
     * carrying them to another phone would just leave broken entries. Text
     * clips travel.
     *
     * Clips marked sensitive are dropped too, whatever their kind. A backup
     * file is the one place a password that expires in five minutes on the
     * device would live forever — and backups get mailed to yourself, dropped
     * in cloud folders, and shared to ask for help with a setting.
     */
    private fun portableClipboard(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        val items = obj["items"] as? JsonArray ?: return element
        val kept = items.filter { item ->
            val entry = item as? JsonObject
            val kind = entry?.get("kind") as? JsonPrimitive
            val sensitive = (entry?.get("sensitive") as? JsonPrimitive)?.booleanOrNull == true
            !sensitive && (kind?.contentOrNull ?: "TEXT") in TEXTUAL_CLIP_KINDS
        }
        return buildJsonObject { put("items", JsonArray(kept)) }
    }

    /**
     * Takes synced clipboard [portable] (text clips only, the same view
     * [portableClipboard] exports) into this phone's history without losing
     * what never syncs: image, file and sensitive clips stay, and so does
     * every other field of the file. The synced clips replace this phone's
     * text clips, which the merge already accounted for.
     */
    suspend fun applySyncedClipboard(portable: JsonObject) {
        val path = "clipboard/history.json"
        val local = readStore(path) as? JsonObject ?: JsonObject(emptyMap())
        val localItems = local["items"] as? JsonArray ?: JsonArray(emptyList())
        val stays = localItems.filter { item ->
            val entry = item as? JsonObject
            val kind = (entry?.get("kind") as? JsonPrimitive)?.contentOrNull ?: "TEXT"
            val sensitive = (entry?.get("sensitive") as? JsonPrimitive)?.booleanOrNull == true
            sensitive || kind !in TEXTUAL_CLIP_KINDS
        }
        val incoming = portable["items"] as? JsonArray ?: JsonArray(emptyList())
        writeStore(path, JsonObject(local + ("items" to JsonArray(incoming + stays))))
    }

    /** Relative path of the sticker manifest, the one file that isn't binary. */
    private val stickerManifestPath =
        "${StickerPackStore.DIR_NAME}/packs.json"

    /**
     * Pack and file names a restore will accept: exactly the shape this app
     * generates. No separators, and no leading dot, so "." and ".." can't
     * match at all.
     */
    private val SAFE_STICKER_NAME = Regex("""[A-Za-z0-9_-]+(\.[A-Za-z0-9]+)?""")

    /**
     * Sticker packs as `{manifest, files}`, with every image base64'd beside
     * the manifest the way theme backgrounds travel. A manifest alone would
     * restore a list of pack names with no pictures in them.
     *
     * Only the stickers the manifest names, which is what keeps the photos
     * they were cut out of — `stickers/.originals`, see
     * [StickerPackStore] — out of a file the user shares.
     */
    private fun stickerSection(): JsonElement? {
        val manifest = readStore(stickerManifestPath) ?: return null
        val root = File(context.filesDir, StickerPackStore.DIR_NAME)
        val files = buildJsonObject {
            for (pack in StickerPackStore.get(context).packs()) {
                for (sticker in pack.stickers) {
                    val file = File(File(root, pack.id), sticker.fileName)
                    if (!file.isFile) continue
                    val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
                    put(
                        "${pack.id}/${sticker.fileName}",
                        JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)),
                    )
                }
            }
        }
        if (files.isEmpty()) return null
        return buildJsonObject {
            put("manifest", manifest)
            put("files", files)
        }
    }

    /** Replaces the sticker directory with the bundle's packs and images. */
    private fun restoreStickers(section: JsonObject): Boolean {
        val manifest = section["manifest"] as? JsonObject ?: return false
        val files = section["files"] as? JsonObject ?: JsonObject(emptyMap())
        val root = File(context.filesDir, StickerPackStore.DIR_NAME)
        return runCatching {
            root.deleteRecursively()
            root.mkdirs()
            val rootPath = root.canonicalPath
            for ((path, value) in files) {
                // Keys come from a file someone else wrote. Split them
                // ourselves, and accept only names that could have been
                // generated here — then confirm against the canonical path,
                // so no combination of dots or separators can land outside.
                val parts = path.split('/')
                if (parts.size != 2) continue
                val (packId, name) = parts
                if (!SAFE_STICKER_NAME.matches(packId) || !SAFE_STICKER_NAME.matches(name)) continue
                val packDir = File(root, packId)
                val target = File(packDir, name)
                if (packDir.canonicalPath != "$rootPath${File.separator}$packId") continue
                if (target.canonicalPath != "${packDir.canonicalPath}${File.separator}$name") continue
                val bytes = (value as? JsonPrimitive)?.contentOrNull
                    ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
                    ?: continue
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            File(root, "packs.json").writeText(manifest.toString())
            // Whatever the bundle claimed but couldn't deliver is dropped here.
            StickerPackStore.get(context).reload()
            true
        }.getOrDefault(false)
    }

    /** Relative path of the icon-pack manifest, the one file that isn't binary. */
    private val iconManifestPath = "${IconPackStore.DIR_NAME}/packs.json"

    /** Same shape as [SAFE_STICKER_NAME]: what a restore will accept for a pack id or file name. */
    private val SAFE_ICON_NAME = Regex("""[A-Za-z0-9_-]+(\.[A-Za-z0-9]+)?""")

    /**
     * Icon packs as `{manifest, files}`, with every SVG base64'd beside the
     * manifest the way sticker images travel. A manifest alone would restore a
     * list of pack names with no icons in them.
     */
    private fun iconSection(): JsonElement? {
        val manifest = readStore(iconManifestPath) ?: return null
        val store = IconPackStore.get(context)
        val root = File(context.filesDir, IconPackStore.DIR_NAME)
        val files = buildJsonObject {
            for (pack in store.packs()) {
                for (slot in pack.slots) {
                    val file = store.fileFor(pack.id, slot) ?: continue
                    if (!file.isFile) continue
                    val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
                    put(
                        "${pack.id}/${IconPackStore.fileNameFor(slot)}",
                        JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)),
                    )
                }
            }
        }
        if (files.isEmpty()) return null
        return buildJsonObject {
            put("manifest", manifest)
            put("files", files)
        }
    }

    /** Replaces the icon-pack directory with the bundle's packs and SVGs. */
    private fun restoreIcons(section: JsonObject): Boolean {
        val manifest = section["manifest"] as? JsonObject ?: return false
        val files = section["files"] as? JsonObject ?: JsonObject(emptyMap())
        val root = File(context.filesDir, IconPackStore.DIR_NAME)
        return runCatching {
            root.deleteRecursively()
            root.mkdirs()
            val rootPath = root.canonicalPath
            for ((path, value) in files) {
                // Keys come from a file someone else wrote; only accept names
                // this app could have generated, then confirm against the
                // canonical path, so no combination of dots or separators can
                // land outside the icon-pack directory.
                val parts = path.split('/')
                if (parts.size != 2) continue
                val (packId, name) = parts
                if (!SAFE_ICON_NAME.matches(packId) || !SAFE_ICON_NAME.matches(name)) continue
                val packDir = File(root, packId)
                val target = File(packDir, name)
                if (packDir.canonicalPath != "$rootPath${File.separator}$packId") continue
                if (target.canonicalPath != "${packDir.canonicalPath}${File.separator}$name") continue
                val bytes = (value as? JsonPrimitive)?.contentOrNull
                    ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
                    ?: continue
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            File(root, "packs.json").writeText(manifest.toString())
            // Whatever the bundle claimed but couldn't deliver is dropped here.
            IconPackStore.get(context).reload()
            true
        }.getOrDefault(false)
    }

    /** A path segment safe to use as-is: no separator, no `.`/`..` climb. */
    private fun isSafeSegment(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name

    /**
     * Custom word lists as a flat `{ "langId/fileName": base64 }` map — there
     * is no manifest to carry separately, the files on disk under
     * [CustomDictionaries.root] are the whole of the state.
     */
    private fun wordlistsSection(): JsonElement? {
        val root = CustomDictionaries.root(context.filesDir)
        val files = buildJsonObject {
            for (langDir in root.listFiles().orEmpty()) {
                if (!langDir.isDirectory) continue
                for (file in CustomDictionaries.lists(context.filesDir, langDir.name)) {
                    val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
                    put("${langDir.name}/${file.name}", JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)))
                }
            }
        }
        if (files.isEmpty()) return null
        return files
    }

    /** Replaces the custom-word-list directory with the bundle's lists. */
    private fun restoreWordlists(section: JsonObject): Boolean {
        val root = CustomDictionaries.root(context.filesDir)
        return runCatching {
            root.deleteRecursively()
            root.mkdirs()
            val rootPath = root.canonicalPath
            for ((path, value) in section) {
                // Same defence as stickers/icons: only names this app could
                // have generated, confirmed against the canonical path so no
                // combination of dots or separators lands outside the folder.
                val parts = path.split('/')
                if (parts.size != 2) continue
                val (langId, name) = parts
                if (!isSafeSegment(langId) || !isSafeSegment(name) || !name.endsWith(".txt")) continue
                val langDir = File(root, langId)
                val target = File(langDir, name)
                if (langDir.canonicalPath != "$rootPath${File.separator}$langId") continue
                if (target.canonicalPath != "${langDir.canonicalPath}${File.separator}$name") continue
                val bytes = (value as? JsonPrimitive)?.contentOrNull
                    ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
                    ?: continue
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            true
        }.getOrDefault(false)
    }

    /**
     * The vocabulary section: every pack file the catalogue cannot bring back
     * (user-made lists, imported files, and each one's `.off` state carried in
     * its name), the ids of the catalogue packs so a restore can offer to
     * download them again, and the learning record.
     */
    private fun vocabSection(): JsonElement? {
        val root = VocabPacks.root(context.filesDir)
        val packs = buildJsonObject {
            for (langDir in root.listFiles().orEmpty()) {
                if (!langDir.isDirectory) continue
                for (file in VocabPacks.files(context.filesDir, langDir.name)) {
                    if (VocabPacks.isCatalogPack(file)) continue
                    val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
                    put("${langDir.name}/${file.name}", JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)))
                }
            }
        }
        val catalogIds = buildJsonArray {
            for (langDir in root.listFiles().orEmpty()) {
                if (!langDir.isDirectory) continue
                for (file in VocabPacks.files(context.filesDir, langDir.name)) {
                    if (VocabPacks.isCatalogPack(file)) add(JsonPrimitive(VocabPacks.packIdOf(file)))
                }
            }
        }
        val progress = readStore(VocabProgress.FILE_PATH)
        if (packs.isEmpty() && catalogIds.isEmpty() && progress == null) return null
        return buildJsonObject {
            put("packs", packs)
            put("catalogIds", catalogIds)
            progress?.let { put("progress", it) }
        }
    }

    /**
     * Restores the vocabulary section. Only the user's own files are replaced —
     * a downloaded catalogue pack on this device stays, since the bundle never
     * carried it — and the learning record is written whole.
     */
    private fun restoreVocab(section: JsonObject): Boolean = runCatching {
        val root = VocabPacks.root(context.filesDir)
        root.mkdirs()
        val rootPath = root.canonicalPath
        for (langDir in root.listFiles().orEmpty()) {
            if (!langDir.isDirectory) continue
            for (file in VocabPacks.files(context.filesDir, langDir.name)) {
                if (!VocabPacks.isCatalogPack(file)) VocabPacks.remove(file)
            }
        }
        val packs = section["packs"]?.jsonObject.orEmpty()
        for ((path, value) in packs) {
            val parts = path.split('/')
            if (parts.size != 2) continue
            val (langId, name) = parts
            val plain = name.removeSuffix(VocabPacks.DISABLED_SUFFIX)
            if (!isSafeSegment(langId) || !isSafeSegment(name) || !plain.endsWith(".${VocabPackFile.FILE_EXTENSION}")) continue
            val langDir = File(root, langId)
            val target = File(langDir, name)
            if (langDir.canonicalPath != "$rootPath${File.separator}$langId") continue
            if (target.canonicalPath != "${langDir.canonicalPath}${File.separator}$name") continue
            val bytes = (value as? JsonPrimitive)?.contentOrNull
                ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
                ?: continue
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
        section["progress"]?.let { writeStore(VocabProgress.FILE_PATH, it) }
        true
    }.getOrDefault(false)

    /**
     * The swipe-style section: the three stores behind [forgetSwipeStyle], each
     * under its own key, and none of them when the user has never swiped.
     *
     * Embedded verbatim the way every other file-backed section is, so this
     * repository never has to model a shape store's internals. Bounded without
     * a cap of its own: the shapes file is the big one, and it holds at most
     * `GlideShapeStore.MAX_WORDS` words of a few hundred bytes each at the
     * default three shapes a word, about 2 KB at the most a user can ask for.
     */
    private fun swipeSection(): JsonElement? {
        val parts = SWIPE_SECTION_KEYS.mapNotNull { (key, path) ->
            readStore(path)?.let { key to it }
        }
        if (parts.isEmpty()) return null
        return JsonObject(parts.toMap())
    }

    /**
     * How many words the swipe-style section has a learned shape for, counted
     * once across every grid they were drawn on: the same word in portrait and
     * landscape is one word the user has taught, not two.
     */
    private fun swipeSectionWordCount(section: JsonElement): Int {
        val layouts = (section as? JsonObject)
            ?.get(SWIPE_SHAPES_KEY)?.jsonObject
            ?.get("layouts")?.jsonObject
            ?: return 0
        val words = HashSet<String>()
        for (layout in layouts.values) {
            (layout as? JsonObject)?.let { words.addAll(it.keys) }
        }
        return words.size
    }

    /**
     * Restores the swipe-style section, writing each store's file whole. The
     * caller signals with the swipe-style version rather than the lexicon's:
     * the lexicon signal also empties the keyboard's learning buffer, and how
     * the user draws says nothing about the words waiting there.
     */
    private fun restoreSwipeStyle(section: JsonObject): Boolean = runCatching {
        var any = false
        for ((key, path) in SWIPE_SECTION_KEYS) {
            val element = section[key] as? JsonObject ?: continue
            if (writeStore(path, element)) any = true
        }
        any
    }.getOrDefault(false)

    /**
     * Restores the addon repository list, merging rather than replacing.
     *
     * Merging because the two sides are both just bookmarks: a repository the
     * user added on this device is no less wanted for not being in the backup,
     * and a duplicate is decided by manifest URL. Cached manifests are dropped
     * — they re-fetch on the next visit, and a stale one from another device
     * would show addons at versions this device never saw.
     */
    private fun restoreAddonRepos(section: JsonObject): Boolean = runCatching {
        val incoming = section["repos"]?.jsonArray ?: return false
        val file = File(context.filesDir, "addons/repos.json")
        val existing = runCatching {
            bundleJson.parseToJsonElement(file.readText()).jsonObject["repos"]?.jsonArray
        }.getOrNull().orEmpty()

        val merged = LinkedHashMap<String, JsonElement>()
        for (element in existing + incoming) {
            val obj = element as? JsonObject ?: continue
            val url = (obj["manifestUrl"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.startsWith("https://") } ?: continue
            merged.putIfAbsent(
                url,
                JsonObject(obj - "cachedManifest" - "fetchedAt"),
            )
        }
        if (merged.isEmpty()) return false

        file.parentFile?.mkdirs()
        file.writeText(
            bundleJson.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("version", JsonPrimitive(1))
                    put("repos", JsonArray(merged.values.toList()))
                },
            ),
        )
        AddonStore.attach(context)
        true
    }.getOrDefault(false)

    /**
     * Roughly how large the image-carrying sections of [sections] would make a
     * bundle, without building one.
     *
     * Three of the sections embed whole files as base64, and nothing caps how
     * many bytes that is: sticker packs are capped by count, not size, and
     * imported word lists are not capped at all. [exportConfig] holds the
     * result as a single string, so a large enough collection is an
     * out-of-memory kill rather than a slow export — and the automatic backup
     * runs inside the keyboard's own process, where that takes the keyboard
     * down with it.
     *
     * So the automatic path asks first and drops those sections when the answer
     * is too big. Base64 is four bytes out for every three in; the JSON around
     * it is noise by comparison.
     */
    fun embeddedByteEstimate(sections: Set<ConfigBackup.Section>): Long {
        fun bytesUnder(dir: File): Long =
            if (!dir.isDirectory) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        var onDisk = 0L
        if (ConfigBackup.Section.STICKERS in sections) {
            onDisk += bytesUnder(File(context.filesDir, StickerPackStore.DIR_NAME))
        }
        if (ConfigBackup.Section.ICONS in sections) {
            onDisk += bytesUnder(File(context.filesDir, IconPackStore.DIR_NAME))
        }
        if (ConfigBackup.Section.WORDLISTS in sections) {
            onDisk += bytesUnder(CustomDictionaries.root(context.filesDir))
        }
        return onDisk * 4 / 3
    }

    /**
     * Builds a full-config bundle from the chosen [sections]. A section whose
     * store is empty or absent is simply left out of the file.
     */
    suspend fun exportConfig(
        sections: Set<ConfigBackup.Section>,
        includeSecrets: Boolean,
        appVersion: Int,
        appVersionName: String,
        /** Settings left out on top of the usual ones; see [AutoBackupRunner]. */
        excludeKeys: Set<String> = emptySet(),
    ): String {
        val prefs = context.dataStore.data.first()
        val out = LinkedHashMap<ConfigBackup.Section, JsonElement>()
        if (ConfigBackup.Section.SETTINGS in sections) {
            out[ConfigBackup.Section.SETTINGS] =
                SettingsBackup.encodeSettings(
                    prefs,
                    includeSecrets,
                    exclude = SettingsBackup.THEME_KEYS + SettingsBackup.TRANSIENT_KEYS + excludeKeys,
                )
        }
        if (ConfigBackup.Section.THEMES in sections) {
            prefs[CUSTOM_THEMES]?.takeIf { it.isNotBlank() }?.let { raw ->
                // Embed each theme's background image as base64 so it travels with
                // the bundle instead of a device-local path that won't resolve on
                // another phone.
                val themes = ThemeCodec.decodeList(raw).map { it.withEmbeddedImages() }
                if (themes.isNotEmpty()) {
                    runCatching { bundleJson.parseToJsonElement(ThemeCodec.encodeList(themes)) }
                        .getOrNull()
                        ?.let { out[ConfigBackup.Section.THEMES] = it }
                }
            }
        }
        if (ConfigBackup.Section.DICTIONARY in sections) {
            readStore("learning/user_lexicon.json")?.let { lexicon ->
                // The user's rank adjustments (#99) travel inside the
                // dictionary section rather than as a section of their own:
                // the lexicon's parser ignores keys it does not know, so an
                // older build restores the words and drops the key, and a
                // new section would have cost a label, a count and a toggle
                // for a file of a few dozen entries.
                // What the user's fixes taught autocorrect, and where their
                // finger lands when tapping, take the same ride: personal in
                // exactly the dictionary's way, and small.
                val ranks = readStore("learning/word_ranks.json") as? JsonObject
                val corrections = readStore(LEARNED_CORRECTIONS_FILE) as? JsonObject
                val taps = readStore(TAP_MODEL_FILE) as? JsonObject
                out[ConfigBackup.Section.DICTIONARY] = if (lexicon is JsonObject) {
                    JsonObject(
                        lexicon + listOfNotNull(
                            ranks?.let { WORD_RANKS_KEY to it },
                            corrections?.let { LEARNED_CORRECTIONS_KEY to it },
                            taps?.let { TAP_MODEL_KEY to it },
                        ),
                    )
                } else {
                    lexicon
                }
            }
        }
        if (ConfigBackup.Section.CLIPBOARD in sections) {
            readStore("clipboard/history.json")?.let { out[ConfigBackup.Section.CLIPBOARD] = portableClipboard(it) }
        }
        if (ConfigBackup.Section.SNIPPETS in sections) {
            readStore("snippets/snippets.json")?.let { out[ConfigBackup.Section.SNIPPETS] = it }
        }
        if (ConfigBackup.Section.STICKERS in sections) {
            stickerSection()?.let { out[ConfigBackup.Section.STICKERS] = it }
        }
        if (ConfigBackup.Section.ICONS in sections) {
            iconSection()?.let { out[ConfigBackup.Section.ICONS] = it }
        }
        if (ConfigBackup.Section.WORDLISTS in sections) {
            wordlistsSection()?.let { out[ConfigBackup.Section.WORDLISTS] = it }
        }
        if (ConfigBackup.Section.EMOJI in sections) {
            readStore("learning/emoji_usage.json")?.let { out[ConfigBackup.Section.EMOJI] = it }
        }
        if (ConfigBackup.Section.STATISTICS in sections) {
            readStore(TypingStats.FILE_PATH)?.let { out[ConfigBackup.Section.STATISTICS] = it }
        }
        if (ConfigBackup.Section.VOCAB in sections) {
            vocabSection()?.let { out[ConfigBackup.Section.VOCAB] = it }
        }
        if (ConfigBackup.Section.ADDONS in sections) {
            // The repository list only. Cached manifests are re-fetched, and
            // the installed-addon records point at local ids that mean nothing
            // on another device.
            readStore("addons/repos.json")?.let { out[ConfigBackup.Section.ADDONS] = it }
        }
        if (ConfigBackup.Section.SWIPE in sections) {
            swipeSection()?.let { out[ConfigBackup.Section.SWIPE] = it }
        }
        return ConfigBackup.encode(appVersion, appVersionName, out)
    }

    /** How many items each section of a decoded bundle holds, for the dialog. */
    fun describeConfig(parsed: ConfigBackup.Parsed): Map<ConfigBackup.Section, Int> {
        val counts = LinkedHashMap<ConfigBackup.Section, Int>()
        for ((section, element) in parsed.sections) {
            val count = runCatching {
                when (section) {
                    ConfigBackup.Section.SETTINGS -> element.jsonObject.size
                    ConfigBackup.Section.THEMES -> element.jsonArray.size
                    ConfigBackup.Section.DICTIONARY -> element.jsonObject["words"]?.jsonObject?.size ?: 0
                    ConfigBackup.Section.CLIPBOARD -> element.jsonObject["items"]?.jsonArray?.size ?: 0
                    ConfigBackup.Section.SNIPPETS -> element.jsonObject["snippets"]?.jsonArray?.size ?: 0
                    ConfigBackup.Section.STICKERS -> element.jsonObject["files"]?.jsonObject?.size ?: 0
                    ConfigBackup.Section.ICONS -> element.jsonObject["files"]?.jsonObject?.size ?: 0
                    ConfigBackup.Section.WORDLISTS -> element.jsonObject.size
                    ConfigBackup.Section.ADDONS -> element.jsonObject["repos"]?.jsonArray?.size ?: 0
                    // Recents rather than every key in the file: it is the part
                    // of the emoji history a user would recognise as "mine".
                    ConfigBackup.Section.EMOJI -> element.jsonObject["recents"]?.jsonArray?.size ?: 0
                    // Days recorded: the lifetime totals ride along with them.
                    ConfigBackup.Section.STATISTICS ->
                        element.jsonObject["days"]?.jsonObject?.size ?: 0
                    // Words with a learning record; the packs ride along uncounted.
                    ConfigBackup.Section.VOCAB ->
                        element.jsonObject["progress"]?.jsonObject?.get("words")?.jsonObject?.size ?: 0
                    // Words with a learned shape, counted once however many
                    // grids they were drawn on. The hand model and the
                    // corrected readings ride along uncounted: neither is a
                    // number a user would recognise.
                    ConfigBackup.Section.SWIPE -> swipeSectionWordCount(element)
                }
            }.getOrDefault(0)
            counts[section] = count
        }
        return counts
    }

    /** True when a decoded bundle's settings section carries any API key. */
    fun configContainsSecrets(parsed: ConfigBackup.Parsed): Boolean {
        val settings = parsed.sections[ConfigBackup.Section.SETTINGS]?.let { it as? JsonObject } ?: return false
        return settings.keys.any { it in SettingsBackup.SECRET_KEYS }
    }

    sealed interface ConfigImportResult {
        /**
         * [restored] lists the sections written. [settingsFailed] is true when
         * the settings section parsed but left the app unable to read its own
         * settings, so it was rolled back while the other sections still applied.
         */
        data class Applied(
            val restored: List<ConfigBackup.Section>,
            val settingsFailed: Boolean,
        ) : ConfigImportResult
        data object NotABackup : ConfigImportResult
    }

    /**
     * Restores every section present in a full-config bundle. The settings
     * section is verified and rolled back on its own the same way
     * [importSettings] is; the file-backed sections overwrite their store file.
     */
    suspend fun importConfig(text: String): ConfigImportResult {
        val parsed = ConfigBackup.decode(text) ?: return ConfigImportResult.NotABackup
        val restored = ArrayList<ConfigBackup.Section>()
        var settingsFailed = false

        (parsed.sections[ConfigBackup.Section.SETTINGS] as? JsonObject)?.let { obj ->
            val (entries, _) = SettingsBackup.decodeSettings(obj)
            val snapshot = context.dataStore.data.first()
            editPrefs { prefs -> entries.forEach { prefs.put(it) } }
            if (runCancellable { settings.first() }.isSuccess) {
                restored.add(ConfigBackup.Section.SETTINGS)
            } else {
                editPrefs { prefs ->
                    prefs.clear()
                    for ((key, value) in snapshot.asMap()) {
                        @Suppress("UNCHECKED_CAST")
                        prefs[key as Preferences.Key<Any>] = value
                    }
                }
                settingsFailed = true
            }
        }

        (parsed.sections[ConfigBackup.Section.THEMES] as? JsonArray)?.let { array ->
            val decoded = runCatching { ThemeCodec.decodeList(array.toString()) }.getOrNull()
            // Not `decoded` directly: a non-empty array that decodes to nothing
            // is a parse failure, and taking it at face value would replace
            // every custom theme with nothing and report success.
            val themes = ConfigBackup.decodedList(decoded, array.size)
            if (themes != null) {
                // Rebuild any embedded background images onto local storage and
                // strip the base64 before persisting the themes.
                val dir = File(context.filesDir, "theme_images")
                val extracted = themes.map { it.withExtractedImages(dir) }
                editPrefs { it[CUSTOM_THEMES] = ThemeCodec.encodeList(extracted) }
                restored.add(ConfigBackup.Section.THEMES)
            }
        }

        (parsed.sections[ConfigBackup.Section.DICTIONARY] as? JsonObject)?.let { obj ->
            // The rank adjustments ride along under their own key (see the
            // export); split them back out so each store gets its own file.
            val ranks = obj[WORD_RANKS_KEY] as? JsonObject
            val corrections = obj[LEARNED_CORRECTIONS_KEY] as? JsonObject
            val taps = obj[TAP_MODEL_KEY] as? JsonObject
            val lexicon = JsonObject(obj - WORD_RANKS_KEY - LEARNED_CORRECTIONS_KEY - TAP_MODEL_KEY)
            if (writeStore("learning/user_lexicon.json", lexicon)) {
                if (ranks != null) writeStore("learning/word_ranks.json", ranks)
                if (corrections != null) writeStore(LEARNED_CORRECTIONS_FILE, corrections)
                if (taps != null) writeStore(TAP_MODEL_FILE, taps)
                restored.add(ConfigBackup.Section.DICTIONARY)
                bumpLexiconVersion()
            }
        }
        (parsed.sections[ConfigBackup.Section.CLIPBOARD] as? JsonObject)?.let { obj ->
            if (writeStore("clipboard/history.json", obj)) restored.add(ConfigBackup.Section.CLIPBOARD)
        }
        (parsed.sections[ConfigBackup.Section.SNIPPETS] as? JsonObject)?.let { obj ->
            if (writeStore("snippets/snippets.json", obj)) restored.add(ConfigBackup.Section.SNIPPETS)
        }
        (parsed.sections[ConfigBackup.Section.STICKERS] as? JsonObject)?.let { obj ->
            if (restoreStickers(obj)) restored.add(ConfigBackup.Section.STICKERS)
        }
        (parsed.sections[ConfigBackup.Section.ICONS] as? JsonObject)?.let { obj ->
            if (restoreIcons(obj)) restored.add(ConfigBackup.Section.ICONS)
        }
        (parsed.sections[ConfigBackup.Section.WORDLISTS] as? JsonObject)?.let { obj ->
            if (restoreWordlists(obj)) {
                restored.add(ConfigBackup.Section.WORDLISTS)
                bumpCustomDictVersion()
            }
        }
        (parsed.sections[ConfigBackup.Section.ADDONS] as? JsonObject)?.let { obj ->
            if (restoreAddonRepos(obj)) restored.add(ConfigBackup.Section.ADDONS)
        }
        (parsed.sections[ConfigBackup.Section.EMOJI] as? JsonObject)?.let { obj ->
            if (writeStore("learning/emoji_usage.json", obj)) {
                restored.add(ConfigBackup.Section.EMOJI)
                bumpEmojiUsageVersion()
            }
        }
        (parsed.sections[ConfigBackup.Section.STATISTICS] as? JsonObject)?.let { obj ->
            if (writeStore(TypingStats.FILE_PATH, obj)) {
                restored.add(ConfigBackup.Section.STATISTICS)
                // The keyboard holds the counters in memory; without this it
                // saves its own numbers over the ones just restored.
                bumpStatsVersion()
            }
        }

        (parsed.sections[ConfigBackup.Section.VOCAB] as? JsonObject)?.let { obj ->
            if (restoreVocab(obj)) restored.add(ConfigBackup.Section.VOCAB)
        }
        (parsed.sections[ConfigBackup.Section.SWIPE] as? JsonObject)?.let { obj ->
            if (restoreSwipeStyle(obj)) {
                restored.add(ConfigBackup.Section.SWIPE)
                bumpSwipeStyleVersion()
            }
        }
        return ConfigImportResult.Applied(restored, settingsFailed)
    }

    suspend fun bumpLexiconVersion() =
        editPrefs { it[LEXICON_VERSION] = (it[LEXICON_VERSION] ?: 0) + 1 }

    suspend fun bumpCustomDictVersion() =
        editPrefs { it[CUSTOM_DICT_VERSION] = (it[CUSTOM_DICT_VERSION] ?: 0) + 1 }

    suspend fun setTypingStatsEnabled(value: Boolean) =
        editPrefs { it[TYPING_STATS_ENABLED] = value }

    suspend fun bumpStatsVersion() =
        editPrefs { it[STATS_VERSION] = (it[STATS_VERSION] ?: 0) + 1 }

    suspend fun setAutoDownloadLanguageData(value: Boolean) =
        editPrefs { it[AUTO_DOWNLOAD_LANGUAGE_DATA] = value }

    suspend fun setAutoPairRomanized(value: Boolean) =
        editPrefs { it[AUTO_PAIR_ROMANIZED] = value }

    suspend fun setMorseCommitMs(value: Int) =
        editPrefs { it[MORSE_COMMIT_MS] = value.coerceIn(MorseCommitMsRange) }

    /**
     * Forgets every app's remembered layout, leaving the feature on.
     *
     * The map is written invisibly as you switch language inside an app, has
     * no viewer, and had no way out: one accidental switch pinned that app to
     * the wrong language for good.
     */
    suspend fun clearPerAppLayouts() = editPrefs { it.remove(PER_APP_LAYOUT_MAP) }

    suspend fun setEmojiAutoDownloadKeywords(value: Boolean) =
        editPrefs { it[EMOJI_AUTO_DOWNLOAD_KEYWORDS] = value }

    /** Switches one language's emoji keyword packs on or off; see [EmojiSettings.disabledKeywordLangs]. */
    suspend fun setEmojiKeywordsEnabled(langId: String, enabled: Boolean) =
        editPrefs {
            val off = it[EMOJI_DISABLED_KEYWORD_LANGS].orEmpty()
            it[EMOJI_DISABLED_KEYWORD_LANGS] = if (enabled) off - langId else off + langId
        }

    suspend fun setAnimatedEmoji(value: Boolean) =
        editPrefs { it[EMOJI_ANIMATED] = value }

    suspend fun setSendEmojiAsSticker(value: Boolean) =
        editPrefs { it[EMOJI_SEND_AS_STICKER] = value }

    /**
     * Rewrites the category tab order; see [EmojiSettings.categoryOrder]. The
     * ids are stored as given, including categories this build's catalog does
     * not have — a keyword pack may add one back, and dropping it here would
     * lose the place the user put it.
     */
    suspend fun setEmojiCategoryOrder(order: List<String>) =
        editPrefs {
            val clean = order.map(String::trim).filter(String::isNotEmpty).distinct()
            if (clean.isEmpty()) it.remove(EMOJI_CATEGORY_ORDER)
            else it[EMOJI_CATEGORY_ORDER] = clean.joinToString(",")
        }

    suspend fun resetEmojiCategoryOrder() = editPrefs { it.remove(EMOJI_CATEGORY_ORDER) }

    /** Shows or hides one category's tab; see [EmojiSettings.hiddenCategories]. */
    suspend fun setEmojiCategoryVisible(category: String, visible: Boolean) =
        editPrefs {
            val hidden = it[EMOJI_HIDDEN_CATEGORIES].orEmpty()
            val next = if (visible) hidden - category else hidden + category
            if (next.isEmpty()) it.remove(EMOJI_HIDDEN_CATEGORIES)
            else it[EMOJI_HIDDEN_CATEGORIES] = next
        }

    /**
     * Rewrites one category's emoji order; see [EmojiSettings.categoryEmojiOrder].
     * An empty [order] drops the entry, which is how a category goes back to
     * catalog order — storing an empty list would mean the same thing but
     * leave a growing map of nothing behind.
     */
    suspend fun setEmojiCategoryEmojiOrder(category: String, order: List<String>) =
        editPrefs { prefs ->
            val current = decodeEmojiOrder(prefs[EMOJI_CATEGORY_EMOJI_ORDER])
            val clean = order.filter { it.isNotEmpty() }.distinct()
            val next = if (clean.isEmpty()) current - category else current + (category to clean)
            if (next.isEmpty()) prefs.remove(EMOJI_CATEGORY_EMOJI_ORDER)
            else prefs[EMOJI_CATEGORY_EMOJI_ORDER] = encodeEmojiOrder(next)
        }

    /** Puts every category's emoji, and the tabs themselves, back in catalog order. */
    suspend fun resetEmojiOrder() = editPrefs {
        it.remove(EMOJI_CATEGORY_ORDER)
        it.remove(EMOJI_HIDDEN_CATEGORIES)
        it.remove(EMOJI_CATEGORY_EMOJI_ORDER)
    }

    suspend fun bumpEmojiKeywordPackVersion() =
        editPrefs {
            it[EMOJI_KEYWORD_PACK_VERSION] = (it[EMOJI_KEYWORD_PACK_VERSION] ?: 0) + 1
        }

    /**
     * Says the emoji history file was rewritten from outside the keyboard, so
     * the running IME re-reads it. Without this an import lands under a live
     * in-memory copy that overwrites it again at the end of the next field.
     */
    suspend fun bumpEmojiUsageVersion() =
        editPrefs { it[EMOJI_USAGE_VERSION] = (it[EMOJI_USAGE_VERSION] ?: 0) + 1 }

    suspend fun setEmojiRecentsLimit(value: Int) =
        editPrefs { it[EMOJI_RECENTS_LIMIT] = value.coerceIn(EmojiRecentsRange) }

    suspend fun setMediaGridColumns(value: Int) =
        editPrefs { it[MEDIA_GRID_COLUMNS] = value.coerceIn(2, 5) }

    /**
     * Wipes the emoji history file: recents, usage counts, favourites and the
     * per-emoji variant picks. Deletes rather than empties, and bumps the
     * version so a running keyboard drops its in-memory copy instead of
     * saving it back over the wipe.
     */
    suspend fun clearEmojiHistory() {
        runCatching { File(context.filesDir, "learning/emoji_usage.json").delete() }
        bumpEmojiUsageVersion()
    }

    /**
     * Wipes everything the keyboard picked up from typing: the learned-word
     * lexicon, the emoji history, autocorrect's revert memory and the
     * language-mix signal.
     *
     * Bumps [KeyboardSettings.lexiconVersion], which is the signal a running
     * keyboard watches to drop all of those in-memory copies at once. Deleting
     * the files without it looks like it worked and then loses: the keyboard
     * still holds the old data and writes it straight back on its next save.
     * That is what the Privacy screen's button did before this existed, and
     * why the Storage screen's delete has always bumped.
     *
     * The Chinese, Japanese and Cantonese history is one of these files, but
     * its store lives in `:core:input`, which this module does not depend on,
     * so the caller clears that copy itself.
     */
    suspend fun clearLearnedData() {
        for (path in LEARNED_DATA_FILES) {
            runCatching { File(context.filesDir, path).delete() }
        }
        bumpLexiconVersion()
        bumpEmojiUsageVersion()
    }

    suspend fun setEmojiFont(value: EmojiFontChoice) =
        editPrefs { it[EMOJI_FONT] = value.name }

    /**
     * Picks an emoji face from the font library and switches to it in one
     * write, the same pairing as [setKeySoundCustomId].
     */
    suspend fun setInstalledEmojiFont(fontId: String) =
        editPrefs {
            it[EMOJI_FONT_INSTALLED_ID] = fontId
            if (fontId.isNotBlank()) it[EMOJI_FONT] = EmojiFontChoice.INSTALLED.name
        }

    /**
     * Drops [fontId] as the emoji face if it is the one selected, falling back
     * to the system emoji font. Called when the font is deleted.
     */
    suspend fun forgetInstalledEmojiFont(fontId: String) =
        editPrefs {
            if (it[EMOJI_FONT_INSTALLED_ID] != fontId) return@editPrefs
            it[EMOJI_FONT_INSTALLED_ID] = ""
            if (it[EMOJI_FONT] == EmojiFontChoice.INSTALLED.name) {
                it[EMOJI_FONT] = EmojiFontChoice.SYSTEM.name
            }
        }

    /**
     * Drops [fontId] — a settings font-id, so `installed:` and all — from every
     * slot that names it: the English face, the Bengali face, and any per-script
     * override. Called when an installed font is deleted.
     *
     * All three, not just the one the user was looking at: a font picked for
     * Devanagari is invisible from the English picker, so deleting the file from
     * anywhere else would leave that script rendering as the fallback while the
     * script picker still showed the font selected.
     */
    suspend fun forgetInstalledFont(fontId: String) =
        editPrefs { prefs ->
            if (prefs[KEY_FONT_ID] == fontId) prefs[KEY_FONT_ID] = DEFAULT_FONT_ID
            if (prefs[BENGALI_FONT_ID] == fontId) prefs[BENGALI_FONT_ID] = DEFAULT_FONT_ID
            val scripts = prefs[SCRIPT_FONT_IDS]?.let { decodeScriptFontIds(it) }.orEmpty()
            val next = scripts.filterValues { it != fontId }
            if (next.size != scripts.size) prefs[SCRIPT_FONT_IDS] = encodeScriptFontIds(next)
        }

    /**
     * Drops a deleted key sound, falling the style back to Click. Left pointing
     * at a missing file the keyboard would still make a sound — the player falls
     * back to the system click — but the settings screen would show Custom
     * selected with nothing under it.
     */
    suspend fun forgetKeySound(soundId: String) =
        editPrefs {
            if (it[KEY_SOUND_CUSTOM_ID] != soundId) return@editPrefs
            it[KEY_SOUND_CUSTOM_ID] = ""
            if (it[KEY_SOUND_STYLE] == KeySoundStyle.CUSTOM.name) {
                it[KEY_SOUND_STYLE] = KeySoundStyle.CLICK.name
            }
        }

    /** [forgetKeySound] for a deleted sound pack. */
    suspend fun forgetKeySoundPack(packId: String) =
        editPrefs {
            if (it[KEY_SOUND_PACK_ID] != packId) return@editPrefs
            it[KEY_SOUND_PACK_ID] = ""
            if (it[KEY_SOUND_STYLE] == KeySoundStyle.PACK.name) {
                it[KEY_SOUND_STYLE] = KeySoundStyle.CLICK.name
            }
        }

    suspend fun setAutoApostrophe(value: Boolean) =
        editPrefs { it[AUTO_APOSTROPHE] = value }

    suspend fun setHapticFeedback(value: Boolean) =
        editPrefs { it[HAPTIC] = value }

    suspend fun setHapticStrengthMs(value: Int) =
        editPrefs { it[HAPTIC_STRENGTH] = value.coerceIn(5, 60) }

    suspend fun setHapticAmplitude(value: Int) =
        editPrefs { it[HAPTIC_AMPLITUDE] = value.coerceIn(1, 255) }

    suspend fun setHapticStyle(value: HapticStyle) =
        editPrefs { it[HAPTIC_STYLE] = value.name }

    suspend fun setHapticOnLongPress(value: Boolean) =
        editPrefs { it[HAPTIC_ON_LONG_PRESS] = value }

    suspend fun setHapticOnLongPressRelease(value: Boolean) =
        editPrefs { it[HAPTIC_ON_LONG_PRESS_RELEASE] = value }

    suspend fun setVibrateOnSpace(value: Boolean) =
        editPrefs { it[FEEDBACK_VIBRATE_SPACE] = value }

    suspend fun setVibrateOnDeleteSwipe(value: Boolean) =
        editPrefs { it[FEEDBACK_VIBRATE_DELETE_SWIPE] = value }

    suspend fun setVibrateOnRepeat(value: Boolean) =
        editPrefs { it[FEEDBACK_VIBRATE_REPEAT] = value }

    suspend fun setSoundOnRepeat(value: Boolean) =
        editPrefs { it[FEEDBACK_SOUND_REPEAT] = value }

    suspend fun setRespectSystemTouchFeedback(value: Boolean) =
        editPrefs { it[FEEDBACK_RESPECT_SYSTEM_TOUCH] = value }

    suspend fun setToastOnCopy(value: Boolean) =
        editPrefs { it[FEEDBACK_TOAST_ON_COPY] = value }

    suspend fun setHapticsRespectDnd(value: Boolean) =
        editPrefs { it[FEEDBACK_HAPTICS_RESPECT_DND] = value }

    suspend fun setKeySound(value: Boolean) =
        editPrefs { it[KEY_SOUND] = value }

    suspend fun setKeyPopup(value: Boolean) =
        editPrefs { it[KEY_POPUP] = value }

    suspend fun setKeyPopupMinDurationMs(value: Int) =
        editPrefs { it[KEY_POPUP_MIN_DURATION] = value.coerceIn(0, 300) }

    suspend fun setKeyPopupMaxDurationMs(value: Int) =
        editPrefs { it[KEY_POPUP_MAX_DURATION] = value.coerceIn(400, 2000) }

    suspend fun setKeyPopupOnKey(value: Boolean) =
        editPrefs { it[KEY_POPUP_ON_KEY] = value }

    suspend fun setKeyPopupInNumericFields(value: Boolean) =
        editPrefs { it[KEY_POPUP_IN_NUMERIC] = value }

    suspend fun setKeyPopupCornerRadiusDp(value: Int) =
        editPrefs { it[KEY_POPUP_RADIUS] = value.coerceIn(0, 40) }

    suspend fun setKeyPopupShape(value: KeyShapeKind) =
        editPrefs { it[KEY_POPUP_SHAPE] = value.name }

    suspend fun setPopupFontScale(value: Float) =
        editPrefs { it[POPUP_FONT_SCALE] = value.coerceIn(0.7f, 1.6f) }

    /**
     * Text size of the long-press alternates. Reaches 3.2 where the bubble stops
     * at 1.6: the alternates popup grows to hold whatever it is given, so the
     * ceiling is legibility rather than a fixed box (issue #64).
     */
    suspend fun setAlternatesFontScale(value: Float) =
        editPrefs { it[ALTERNATES_FONT_SCALE] = value.coerceIn(0.7f, 3.2f) }

    suspend fun setAlternatesPaddingDp(value: Int) =
        editPrefs { it[ALTERNATES_PADDING] = value.coerceIn(0, 32) }

    /** 0 is the automatic wrap; anything else is clamped into [AlternateColumnsRange]. */
    suspend fun setAlternatesColumns(value: Int) =
        editPrefs {
            it[ALTERNATES_COLUMNS] =
                if (value <= 0) 0 else value.coerceIn(AlternateColumnsRange)
        }

    suspend fun setAlternatesNearestFirst(value: Boolean) =
        editPrefs { it[ALTERNATES_NEAREST_FIRST] = value }

    suspend fun setAlternatesHoldToSelect(value: Boolean) =
        editPrefs { it[ALTERNATES_HOLD_TO_SELECT] = value }

    suspend fun setKeyPopupFloatingOffsetYDp(value: Int) =
        editPrefs { it[KEY_POPUP_OFFSET_Y] = value.coerceIn(0, 96) }

    suspend fun setKeyPopupFloatingOffsetXDp(value: Int) =
        editPrefs { it[KEY_POPUP_OFFSET_X] = value.coerceIn(-64, 64) }

    /** Null clears the key, which puts the bubble back on the theme's colour. */
    suspend fun setKeyPopupBackgroundColor(value: Long?) =
        editPrefs {
            if (value == null) it.remove(KEY_POPUP_BACKGROUND) else it[KEY_POPUP_BACKGROUND] = value
        }

    /** Null clears the key; see [setKeyPopupBackgroundColor]. */
    suspend fun setKeyPopupTextColor(value: Long?) =
        editPrefs {
            if (value == null) it.remove(KEY_POPUP_TEXT_COLOR) else it[KEY_POPUP_TEXT_COLOR] = value
        }

    /** Writes the height of whichever bubble style is on; see [KeyPopupSettings.heightDp]. */
    suspend fun setKeyPopupHeightDp(value: Int) =
        editPrefs {
            val onKey = it[KEY_POPUP_ON_KEY] ?: KeyPopupSettings().onKey
            val key = if (onKey) KEY_POPUP_HEIGHT else KEY_POPUP_FLOATING_HEIGHT
            it[key] = value.coerceIn(32, 160)
        }

    // ---- accessibility ----

    suspend fun setColorVisionFilter(value: ColorVisionFilter) =
        editPrefs { it[COLOR_VISION_FILTER] = value.name }

    suspend fun setHighContrastKeys(value: Boolean) =
        editPrefs { it[HIGH_CONTRAST_KEYS] = value }

    suspend fun setKeyOutlines(value: Boolean) =
        editPrefs { it[KEY_OUTLINES] = value }

    suspend fun setBoldKeyLabels(value: Boolean) =
        editPrefs { it[BOLD_KEY_LABELS] = value }

    suspend fun setReduceMotion(value: Boolean) =
        editPrefs { it[REDUCE_MOTION] = value }

    suspend fun setScreenReaderMode(value: ScreenReaderMode) =
        editPrefs { it[SCREEN_READER_MODE] = value.name }

    suspend fun setKeyDebounceMs(value: Int) =
        editPrefs { it[KEY_DEBOUNCE_MS] = value.coerceIn(0, 500) }

    suspend fun setNumberRow(value: Boolean) =
        editPrefs { it[NUMBER_ROW] = value }

    suspend fun setAutocorrect(value: Boolean) =
        editPrefs { it[AUTOCORRECT] = value }

    /** Bounds mirror `SuggestionEngine.MIN/MAX_AUTOCORRECT_CONFIDENCE`. */
    suspend fun setAutocorrectConfidence(value: Float) =
        editPrefs { it[AUTOCORRECT_CONFIDENCE] = value.coerceIn(1.5f, 10f) }

    suspend fun setAutocorrectAdaptive(value: Boolean) =
        editPrefs { it[AUTOCORRECT_ADAPTIVE] = value }

    suspend fun setAutocorrectUndoMemory(value: UndoMemory) =
        editPrefs { it[AUTOCORRECT_UNDO_MEMORY] = value.name }

    suspend fun setRevertAutocorrectOnBackspace(value: Boolean) =
        editPrefs { it[REVERT_AUTOCORRECT_ON_BACKSPACE] = value }

    suspend fun setAutocorrectSkipAllCaps(value: Boolean) =
        editPrefs { it[AUTOCORRECT_SKIP_ALL_CAPS] = value }

    suspend fun setAutoCapitalize(value: Boolean) =
        editPrefs { it[AUTO_CAPITALIZE] = value }

    suspend fun setDoubleSpacePeriod(value: Boolean) =
        editPrefs { it[DOUBLE_SPACE_PERIOD] = value }

    suspend fun setDoubleSpaceTab(value: Boolean) =
        editPrefs { it[DOUBLE_SPACE_TAB] = value }

    suspend fun setAutoSpaceAfterPunctuation(value: Boolean) =
        editPrefs { it[AUTO_SPACE_AFTER_PUNCTUATION] = value }

    suspend fun setHugPunctuation(value: Boolean) =
        editPrefs { it[HUG_PUNCTUATION] = value }

    suspend fun setLanguagePunctuationSpacing(value: Boolean) =
        editPrefs { it[LANGUAGE_PUNCTUATION_SPACING] = value }

    /** Whitespace can never be a mark, and a very long list is a typo. */
    suspend fun setHugPunctuationMarks(value: String) = editPrefs {
        it[HUG_PUNCTUATION_MARKS] = value
            .filterNot { c -> c.isWhitespace() }
            .take(AutoTextSettings.HUG_PUNCTUATION_MARKS_MAX)
    }

    suspend fun setWrapSelectionWithPair(value: Boolean) =
        editPrefs { it[WRAP_SELECTION_WITH_PAIR] = value }

    suspend fun setAutoCloseBrackets(value: Boolean) =
        editPrefs { it[AUTO_CLOSE_BRACKETS] = value }

    suspend fun setRecapitalizeSelectionWithShift(value: Boolean) =
        editPrefs { it[RECAPITALIZE_SELECTION_WITH_SHIFT] = value }

    suspend fun setSuggestions(value: Boolean) =
        editPrefs { it[SUGGESTIONS] = value }

    suspend fun setShowSuggestionsInAllFields(value: Boolean) =
        editPrefs { it[SHOW_SUGGESTIONS_ALL_FIELDS] = value }

    suspend fun setSuggestionsFirst(value: Boolean) =
        editPrefs { it[SUGGESTIONS_FIRST] = value }

    suspend fun setSuggestionPrimaryCenter(value: Boolean) =
        editPrefs { it[SUGGESTION_PRIMARY_CENTER] = value }

    suspend fun setSuggestionOverflow(value: SuggestionOverflow) =
        editPrefs { it[SUGGESTION_OVERFLOW] = value.name }

    suspend fun setBlockOffensiveWords(value: Boolean) =
        editPrefs { it[BLOCK_OFFENSIVE_WORDS] = value }

    suspend fun setContextRerank(value: Boolean) =
        editPrefs { it[CONTEXT_RERANK] = value }

    suspend fun setLanguageDetection(value: Boolean) =
        editPrefs { it[LANGUAGE_DETECTION] = value }

    suspend fun setLanguageDetectionStrength(value: LanguageDetectionStrength) =
        editPrefs { it[LANGUAGE_DETECTION_STRENGTH] = value.name }

    suspend fun setLanguageDetectionByApp(value: Boolean) =
        editPrefs { it[LANGUAGE_DETECTION_BY_APP] = value }

    suspend fun setPhoneticEnglish(langId: String, enabled: Boolean) =
        editPrefs {
            val on = it[PHONETIC_ENGLISH_LANGS]
                ?: LEGACY_PHONETIC_ENGLISH_LANGS.takeIf { _ -> it[PHONETIC_AUTO_ENGLISH] == true }
                ?: emptySet()
            it[PHONETIC_ENGLISH_LANGS] = if (enabled) on + langId else on - langId
        }

    suspend fun setPhoneticEnglishSwitch(value: Boolean) =
        editPrefs { it[PHONETIC_ENGLISH_SWITCH] = value }

    suspend fun setPhoneticFixedStrip(langId: String, enabled: Boolean) =
        editPrefs {
            val on = it[PHONETIC_FIXED_STRIP_LANGS].orEmpty()
            it[PHONETIC_FIXED_STRIP_LANGS] = if (enabled) on + langId else on - langId
        }

    suspend fun setPhoneticStripSource(langId: String, source: PhoneticStripSource) =
        editPrefs {
            val others = it[PHONETIC_STRIP_SOURCES].orEmpty().filterNot { e -> e.substringBefore('=') == langId }
            it[PHONETIC_STRIP_SOURCES] = others.toSet() + "$langId=${source.name}"
        }

    suspend fun setNumberRowCorrections(value: Boolean) =
        editPrefs { it[NUMBER_ROW_CORRECTIONS] = value }

    suspend fun setNumberPrediction(value: Boolean) =
        editPrefs { it[NUMBER_PREDICTION] = value }

    suspend fun setAutocorrectSplits(value: Boolean) =
        editPrefs { it[AUTOCORRECT_SPLITS] = value }

    suspend fun setAutoSpaceAfterSuggestion(value: Boolean) =
        editPrefs { it[AUTO_SPACE_AFTER_SUGGESTION] = value }

    suspend fun setExpandUserDictShortcuts(value: Boolean) =
        editPrefs { it[EXPAND_USER_DICT_SHORTCUTS] = value }

    suspend fun setUseSystemDictionary(value: Boolean) =
        editPrefs { it[USE_SYSTEM_DICTIONARY] = value }

    suspend fun setSnippetMultiExpand(value: MultiExpandMode) =
        editPrefs { it[SNIPPET_MULTI_EXPAND] = value.name }

    suspend fun setSystemSmartReplies(value: Boolean) =
        editPrefs { it[SYSTEM_SMART_REPLIES] = value }

    suspend fun setRegisterPriors(value: Boolean) =
        editPrefs { it[REGISTER_PRIORS] = value }

    suspend fun setTimingSignalStrength(value: Float) =
        editPrefs { it[TIMING_SIGNAL_STRENGTH] = value.coerceIn(0f, 1f) }

    suspend fun setNumberRowInSymbols(value: Boolean) =
        editPrefs { it[NUMBER_ROW_IN_SYMBOLS] = value }

    suspend fun setBottomRowHeightDp(value: Int) =
        editPrefs { it[BOTTOM_ROW_HEIGHT] = value.coerceIn(0, BottomRowHeightRange.last) }

    suspend fun setSidePadLeftScale(value: Float) =
        editPrefs {
            it[SIDE_PAD_LEFT_SCALE] =
                value.coerceIn(SidePadScaleRange.start, SidePadScaleRange.endInclusive)
        }

    suspend fun setSidePadRightScale(value: Float) =
        editPrefs {
            it[SIDE_PAD_RIGHT_SCALE] =
                value.coerceIn(SidePadScaleRange.start, SidePadScaleRange.endInclusive)
        }

    suspend fun setSplitOnlyOnLargeScreens(value: Boolean) =
        editPrefs { it[SPLIT_ONLY_LARGE] = value }

    /**
     * Puts the floating panel back where it starts: centred, 320 dp wide, at
     * full key height. Its position and size are only ever written by drags,
     * so a panel dragged somewhere awkward otherwise has no way back.
     */
    suspend fun resetFloatingGeometry() = editPrefs {
        it.remove(FLOATING_WIDTH)
        it.remove(FLOATING_HEIGHT_SCALE)
        it.remove(FLOATING_X)
        it.remove(FLOATING_Y)
    }

    /**
     * Puts the whole Size and position group back to its shipped values. The
     * per-row reset controls each do one of these; this is for the user who
     * has moved six of them and wants the keyboard back rather than a tour of
     * which slider they touched.
     */
    suspend fun resetSizeAndPosition() = editPrefs {
        it.remove(KEY_HEIGHT)
        it.remove(NUMBER_ROW_HEIGHT)
        it.remove(BOTTOM_ROW_HEIGHT)
        it.remove(BOTTOM_PADDING)
        it.remove(KEYBOARD_WIDTH_PERCENT)
        it.remove(KEYBOARD_ALIGNMENT)
        it.remove(KEY_GAP_SCALE)
        it.remove(SIDE_PAD_SCALE)
        it.remove(SIDE_PAD_LEFT_SCALE)
        it.remove(SIDE_PAD_RIGHT_SCALE)
        it.remove(KEY_CORNER_RADIUS)
    }

    /**
     * Puts the Appearance screen back to its shipped values.
     *
     * Same reasoning as [resetSizeAndPosition]: twenty-odd rows, each with its
     * own per-row reset, and no way to undo an afternoon of them at once.
     *
     * Theme, font and icon pack are deliberately left alone. They are the three
     * rows on this screen that only lead somewhere else, each has its own screen
     * with its own undo, and a theme is the one appearance choice a user is
     * likely to have spent real time on — a reset for the sliders must not take
     * it with them.
     *
     * The keyboard's own key sizes are not here either. They belong to Layout &
     * size, and [resetSizeAndPosition] is their reset; `KEY_CORNER_RADIUS` is
     * the single overlap, drawn on both screens and reset by both. The toolbar
     * and the toolbox have pages and resets of their own, [resetToolbar] and
     * [resetToolbox].
     */
    suspend fun resetAppearance() = editPrefs {
        it.remove(KEY_CORNER_RADIUS)
        it.remove(FONT_SCALE)
        it.remove(HINT_FONT_SCALE)
        it.remove(HINT_OFFSET)
    }

    /** The Toolbar page's reset: the bar, its labels, the suggestion strip and the tool shape. */
    suspend fun resetToolbar() = editPrefs {
        it.remove(TOOLBAR_ENABLED)
        it.remove(TOOLBAR_SWIPE_DOWN_HIDE)
        it.remove(TOOLBAR_ONLY_HW_KEYBOARD)
        it.remove(REVERSE_TOOLBAR_RTL)
        it.remove(TOOLBAR_GREEDY)
        it.remove(TOOLBAR_HEIGHT)
        it.remove(TOOLBAR_SCROLLABLE)
        it.remove(TOOLBAR_HIDE_WHEN_LOCKED)
        it.remove(TOOLBAR_LABELS)
        it.remove(TOOLBAR_LABEL_SIZE)
        it.remove(SUGGESTION_TEXT_SCALE)
        it.remove(SUGGESTION_CHIP_PADDING)
        it.remove(SUGGESTION_PRIMARY_COLOR)
        it.remove(TOOL_CIRCLE_RADIUS)
        it.remove(TOOL_SHAPE)
        it.remove(TOOLBAR_TOOL_WIDTH)
        it.remove(TOOLBAR_PADDING_TOP)
        it.remove(TOOLBAR_PADDING_BOTTOM)
    }

    /** The Toolbox page's reset: the tool grid's layout, columns, paging and labels. */
    suspend fun resetToolbox() = editPrefs {
        it.remove(TOOLBOX_LAYOUT)
        it.remove(TOOLBOX_COLUMNS)
        it.remove(TOOLBOX_PILL_COLUMNS)
        it.remove(TOOLBOX_PILL_FILLED)
        it.remove(TOOLBOX_PAGINATE)
        it.remove(TOOLBOX_PAGE_SIZE)
        it.remove(TOOLBOX_LABEL_SIZE)
    }

    suspend fun setHoldToTalkMs(value: Int) = editPrefs {
        it[VOICE_HOLD_TO_TALK_MS] = value.coerceIn(HoldToTalkRange.first, HoldToTalkRange.last)
    }

    suspend fun setAiKeepChats(value: Boolean) = editPrefs { it[AI_KEEP_CHATS] = value }

    suspend fun setAiChatEnterSends(value: Boolean) =
        editPrefs { it[AI_CHAT_ENTER_SENDS] = value }

    suspend fun setAiPanelChat(value: Boolean) = editPrefs { it[AI_PANEL_CHAT] = value }

    suspend fun setAiBeforeCursorChars(value: Int) =
        editPrefs { it[AI_BEFORE_CURSOR_CHARS] = value.coerceIn(500, 32_000) }

    suspend fun setShiftCapsLockMs(value: Int) =
        editPrefs { it[SHIFT_CAPS_LOCK_MS] = value.coerceIn(ShiftCapsLockMsRange.first, ShiftCapsLockMsRange.last) }

    suspend fun setShowAllPopupKeys(value: Boolean) =
        editPrefs { it[SHOW_ALL_POPUP_KEYS] = value }

    suspend fun setShiftedPopupKeys(value: Boolean) =
        editPrefs { it[SHIFTED_POPUP_KEYS] = value }

    suspend fun setSymbolsReturnToLetters(value: Boolean) =
        editPrefs { it[SYMBOLS_RETURN_TO_LETTERS] = value }

    /**
     * Persist the characters that send ?123 back to the letters. Whitespace and
     * duplicates are dropped, so "! ? ." and "!?." store the same thing; empty
     * falls back to [DefaultSymbolsReturnChars].
     */
    suspend fun setSymbolsReturnChars(value: String) =
        editPrefs { prefs ->
            val cleaned = value.filterNot { it.isWhitespace() }.toSet().joinToString("")
            if (cleaned.isEmpty()) {
                prefs.remove(SYMBOLS_RETURN_CHARS)
            } else {
                prefs[SYMBOLS_RETURN_CHARS] = cleaned
            }
        }

    /** Persist the currency long-press glyphs; empty list falls back to the built-in set. */
    suspend fun setCurrencyKeys(value: List<String>) =
        editPrefs { prefs ->
            val cleaned = value.map { it.trim() }.filter { it.isNotEmpty() }
            if (cleaned.isEmpty()) prefs.remove(CURRENCY_KEYS) else prefs[CURRENCY_KEYS] = cleaned.joinToString("\n")
        }

    /**
     * Persist the spacebar's long-press keys; an empty list gives the hold back
     * to the language picker (and to the space repeat).
     */
    suspend fun setSpaceHoldKeys(value: List<String>) =
        editPrefs { prefs ->
            val cleaned = value.map { it.trim() }.filter { it.isNotEmpty() }
            if (cleaned.isEmpty()) {
                prefs.remove(SPACE_HOLD_KEYS)
            } else {
                prefs[SPACE_HOLD_KEYS] = cleaned.joinToString("\n")
            }
        }

    /**
     * Turn the fixed-spelling map on or off for one language. Only the
     * switched-off languages are stored, so a language nobody has touched
     * keeps the map without needing an entry.
     */
    suspend fun setSpellingMapEnabled(langId: String, enabled: Boolean) =
        editPrefs {
            val off = it[SPELLING_MAP_OFF_LANGS].orEmpty()
            it[SPELLING_MAP_OFF_LANGS] = if (enabled) off - langId else off + langId
        }

    /**
     * Let one phonetic language's space bar commit a sound-alike dictionary
     * word, or keep it to the letter-for-letter reading. Only the switched-off
     * languages are stored.
     */
    suspend fun setPhoneticSiblingsEnabled(langId: String, enabled: Boolean) =
        editPrefs {
            val off = it[PHONETIC_SIBLINGS_OFF_LANGS].orEmpty()
            it[PHONETIC_SIBLINGS_OFF_LANGS] = if (enabled) off - langId else off + langId
        }

    /**
     * Turn the bundled and downloaded dictionaries on or off for one language,
     * leaving it to the user's imported lists. Only the switched-off languages
     * are stored, so a language nobody has touched keeps the shipped
     * vocabulary without needing an entry.
     */
    suspend fun setShippedDictionaryEnabled(langId: String, enabled: Boolean) =
        editPrefs {
            val off = it[IMPORTED_ONLY_LANGS].orEmpty()
            it[IMPORTED_ONLY_LANGS] = if (enabled) off - langId else off + langId
        }

    /** Turn one language's downloaded word-pair data on or off. */
    suspend fun setWordPairsEnabled(langId: String, enabled: Boolean) =
        editPrefs {
            val off = it[WORD_PAIRS_OFF_LANGS].orEmpty()
            it[WORD_PAIRS_OFF_LANGS] = if (enabled) off - langId else off + langId
        }

    /** Replaces the whole set of optional held-word menu items (#99). */
    suspend fun setWordMenuItems(value: Set<WordMenuItem>) =
        editPrefs { it[WORD_MENU_ITEMS] = value.mapTo(mutableSetOf(WORD_MENU_SYNONYMS_MARK)) { item -> item.name } }

    /** Replaces the synonym sources, order and switches together (#321). */
    suspend fun setSynonymSources(value: List<SynonymSourceChoice>) =
        editPrefs { it[SYNONYM_SOURCES] = SynonymSources.encode(value) }

    suspend fun setRankControl(value: RankControl) =
        editPrefs { it[WORD_RANK_CONTROL] = value.name }

    suspend fun setDeleteEditsImportedLists(value: Boolean) =
        editPrefs { it[DELETE_EDITS_IMPORTED_LISTS] = value }

    suspend fun setLearnFromTextSort(value: LearnFromTextSort) =
        editPrefs { it[LEARN_FROM_TEXT_SORT] = value.name }

    suspend fun setLearnFromTextPairs(value: Boolean) =
        editPrefs { it[LEARN_FROM_TEXT_PAIRS] = value }

    suspend fun setContactSuggestions(value: Boolean) =
        editPrefs { it[CONTACT_SUGGESTIONS] = value }

    suspend fun setContactEmailSuggestions(value: Boolean) =
        editPrefs { it[CONTACT_EMAIL_SUGGESTIONS] = value }

    suspend fun setContactEmailSuggestionsInEmailFields(value: Boolean) =
        editPrefs { it[CONTACT_EMAIL_SUGGESTIONS_IN_EMAIL_FIELDS] = value }

    suspend fun setAppNameSuggestions(value: Boolean) =
        editPrefs { it[APP_NAME_SUGGESTIONS] = value }

    /**
     * Adds a word to the never-suggest blacklist (lowercased, trimmed): the
     * global list, or [languageId]'s own list when one is named (#136).
     */
    suspend fun addSuggestionBlacklistWord(word: String, languageId: String? = null) {
        val normalized = word.trim().lowercase()
        if (normalized.isEmpty()) return
        val key = languageId?.let(::blacklistLanguageKey) ?: SUGGESTION_BLACKLIST
        editPrefs { it[key] = (it[key].orEmpty() + normalized) }
    }

    /**
     * Removes a word from the never-suggest blacklist: from the global list, or
     * from [languageId]'s own list when one is named.
     */
    suspend fun removeSuggestionBlacklistWord(word: String, languageId: String? = null) {
        val normalized = word.trim().lowercase()
        val key = languageId?.let(::blacklistLanguageKey) ?: SUGGESTION_BLACKLIST
        editPrefs { prefs ->
            val next = prefs[key].orEmpty() - normalized
            if (next.isEmpty()) prefs.remove(key) else prefs[key] = next
        }
    }

    /**
     * Removes a word from every blacklist it is on, global and per-language
     * alike — what "Suggest again" and adding the word by hand mean, whichever
     * list the block came from.
     */
    suspend fun removeSuggestionBlacklistWordEverywhere(word: String) {
        val normalized = word.trim().lowercase()
        editPrefs { prefs ->
            val keys = prefs.asMap().keys.filter {
                it == SUGGESTION_BLACKLIST || it.name.startsWith(SUGGESTION_BLACKLIST_LANG_PREFIX)
            }
            for (key in keys) {
                @Suppress("UNCHECKED_CAST")
                val set = key as Preferences.Key<Set<String>>
                val next = prefs[set].orEmpty() - normalized
                if (next.isEmpty()) prefs.remove(set) else prefs[set] = next
            }
        }
    }

    /** Empties every blacklist in one edit; per-word removal is the only other way out. */
    suspend fun clearSuggestionBlacklist() = editPrefs { prefs ->
        val keys = prefs.asMap().keys.filter {
            it == SUGGESTION_BLACKLIST || it.name.startsWith(SUGGESTION_BLACKLIST_LANG_PREFIX)
        }
        for (key in keys) prefs.remove(key)
    }

    suspend fun setSuggestionBlacklistScope(value: BlacklistScope) =
        editPrefs { it[SUGGESTION_BLACKLIST_SCOPE] = value.name }

    suspend fun setInlineEmojiSearch(value: Boolean) =
        editPrefs { it[INLINE_EMOJI_SEARCH] = value }

    suspend fun setInlineAutofill(value: Boolean) =
        editPrefs { it[INLINE_AUTOFILL] = value }

    suspend fun setGestureTyping(value: Boolean) =
        editPrefs { it[GESTURE_TYPING] = value }

    suspend fun setLetterSwipeAction(value: LetterSwipeAction) =
        editPrefs { it[LETTER_SWIPE_ACTION] = value.name }

    suspend fun setGestureSpaceMultiWord(value: Boolean) =
        editPrefs { it[GESTURE_SPACE_MULTI_WORD] = value }

    suspend fun setGestureShiftCapitals(value: Boolean) =
        editPrefs { it[GESTURE_SHIFT_CAPITALS] = value }

    suspend fun setGestureShiftGlideMode(value: ShiftGlideMode) =
        editPrefs { it[GESTURE_SHIFT_MODE] = value.name }

    suspend fun setGestureAmbiguityPicker(value: Boolean) =
        editPrefs { it[GESTURE_AMBIGUITY_PICKER] = value }

    suspend fun setGesturePickerDwellMs(value: Int) =
        editPrefs { it[GESTURE_PICKER_DWELL_MS] = value.coerceIn(GlidePickerDwellMsRange) }

    suspend fun setGesturePickerSensitivity(value: GlidePickerSensitivity) =
        editPrefs { it[GESTURE_PICKER_SENSITIVITY] = value.name }

    suspend fun setGesturePickerHoldToAsk(value: Boolean) =
        editPrefs { it[GESTURE_PICKER_HOLD_TO_ASK] = value }

    suspend fun setGesturePickerChoices(value: Int) =
        editPrefs { it[GESTURE_PICKER_CHOICES] = value.coerceIn(GlidePickerChoicesRange) }

    suspend fun setGestureApostropheKey(value: GlideApostropheKey) =
        editPrefs { it[GESTURE_APOSTROPHE_KEY] = value.name }

    suspend fun setGesturePossessiveKey(value: GlideApostropheKey) =
        editPrefs { it[GESTURE_POSSESSIVE_KEY] = value.name }

    suspend fun setGestureLearnSwipeStyle(value: Boolean) =
        editPrefs { it[GESTURE_LEARN_SWIPE_STYLE] = value }

    /** @see GestureSettings.shapesPerWord */
    suspend fun setGestureShapesPerWord(value: Int) =
        editPrefs { it[GESTURE_SHAPES_PER_WORD] = value.coerceIn(GlideShapesPerWordRange) }

    suspend fun setGestureSearchAllChip(value: Boolean) =
        editPrefs { it[GESTURE_SEARCH_ALL_CHIP] = value }

    /**
     * Deletes everything a swipe style is made of — where the finger lands,
     * and the readings the user corrected — and tells a running keyboard to
     * drop its copies, the way [clearLearnedData] does for the rest of the
     * learning directory — without the lexicon signal, whose reload also
     * empties the keyboard's half-learned words.
     */
    suspend fun forgetSwipeStyle() {
        for (path in SWIPE_STYLE_FILES) runCatching { File(context.filesDir, path).delete() }
        bumpSwipeStyleVersion()
    }

    /**
     * Says the swipe-style files were written from outside the keyboard, so a
     * running one re-reads them. Without it a restore lands under live
     * in-memory copies that write themselves back over it at the end of the
     * next field, exactly as [bumpEmojiUsageVersion] exists to stop.
     */
    suspend fun bumpSwipeStyleVersion() =
        editPrefs { it[GESTURE_SWIPE_STYLE_VERSION] = (it[GESTURE_SWIPE_STYLE_VERSION] ?: 0) + 1 }

    suspend fun setGestureAutoSpace(value: Boolean) =
        editPrefs { it[GESTURE_AUTO_SPACE] = value }

    /** @see GestureSettings.startRadius; the bounds match the slider's. */
    suspend fun setGestureStartRadius(value: Float) =
        editPrefs { it[GESTURE_START_RADIUS] = value.coerceIn(GlideRadiusRange) }

    /** @see GestureSettings.endRadius */
    suspend fun setGestureEndRadius(value: Float) =
        editPrefs { it[GESTURE_END_RADIUS] = value.coerceIn(GlideRadiusRange) }

    /** @see GestureSettings.nearRadius */
    suspend fun setGestureNearRadius(value: Float) =
        editPrefs { it[GESTURE_NEAR_RADIUS] = value.coerceIn(GlideRadiusRange) }

    /** @see GestureSettings.dwellFull; the bounds match the slider's. */
    suspend fun setGestureDwellFull(value: Float) =
        editPrefs { it[GESTURE_DWELL_FULL] = value.coerceIn(GlideDwellFullRange) }

    /** @see GestureSettings.loopDouble */
    suspend fun setGestureLoopDouble(value: Boolean) =
        editPrefs { it[GESTURE_LOOP_DOUBLE] = value }

    /** @see GestureSettings.loopMinArc */
    suspend fun setGestureLoopMinArc(value: Float) =
        editPrefs { it[GESTURE_LOOP_MIN_ARC] = value.coerceIn(GlideLoopMinArcRange) }

    /** @see GestureSettings.loopExtent */
    suspend fun setGestureLoopExtent(value: Float) =
        editPrefs { it[GESTURE_LOOP_EXTENT] = value.coerceIn(GlideLoopExtentRange) }

    /** @see GestureSettings.loopRadius */
    suspend fun setGestureLoopRadius(value: Float) =
        editPrefs { it[GESTURE_LOOP_RADIUS] = value.coerceIn(GlideLoopRadiusRange) }

    /** @see GestureSettings.wiggleDouble */
    suspend fun setGestureWiggleDouble(value: Boolean) =
        editPrefs { it[GESTURE_WIGGLE_DOUBLE] = value }

    /** @see GestureSettings.wiggleExtent */
    suspend fun setGestureWiggleExtent(value: Float) =
        editPrefs { it[GESTURE_WIGGLE_EXTENT] = value.coerceIn(GlideWiggleExtentRange) }

    /** @see GestureSettings.wiggleWeight */
    suspend fun setGestureWiggleWeight(value: Float) =
        editPrefs { it[GESTURE_WIGGLE_WEIGHT] = value.coerceIn(GlideWiggleWeightRange) }

    suspend fun setGestureStartThresholdSlop(value: Float) =
        editPrefs { it[GESTURE_START_THRESHOLD_SLOP] = value.coerceIn(0.5f, 4f) }

    suspend fun setGesturePostTypeCooldownMs(value: Int) =
        editPrefs { it[GESTURE_POST_TYPE_COOLDOWN_MS] = value.coerceIn(0, 500) }

    suspend fun setGestureHandwriteDotCooldownMs(value: Int) =
        editPrefs { it[GESTURE_HANDWRITE_DOT_COOLDOWN_MS] = value.coerceIn(0, 1500) }

    suspend fun setGestureTrailWidthDp(value: Float) =
        editPrefs { it[GESTURE_TRAIL_WIDTH_DP] = value.coerceIn(2f, 24f) }

    suspend fun setGestureTrailDurationMs(value: Int) =
        editPrefs { it[GESTURE_TRAIL_DURATION_MS] = value.coerceIn(100, 1200) }

    /** Zero is a real value here: it is how the trail is switched off. */
    suspend fun setGestureTrailOpacity(value: Float) =
        editPrefs { it[GESTURE_TRAIL_OPACITY] = value.coerceIn(0f, 1f) }

    suspend fun setGestureWordPreview(value: Boolean) =
        editPrefs { it[GESTURE_WORD_PREVIEW] = value }

    suspend fun setGestureWordPreviewOffsetYDp(value: Int) =
        editPrefs { it[GESTURE_WORD_PREVIEW_OFFSET_Y] = value.coerceIn(0, 160) }

    suspend fun setGestureWordPreviewOffsetXDp(value: Int) =
        editPrefs { it[GESTURE_WORD_PREVIEW_OFFSET_X] = value.coerceIn(-80, 80) }

    suspend fun setGestureWordPreviewFontSp(value: Int) =
        editPrefs { it[GESTURE_WORD_PREVIEW_FONT_SP] = value.coerceIn(12, 32) }

    /** Null clears the key, which puts the pill back on the theme's colour. */
    suspend fun setGestureWordPreviewBackground(value: Long?) =
        editPrefs {
            if (value == null) {
                it.remove(GESTURE_WORD_PREVIEW_BACKGROUND)
            } else {
                it[GESTURE_WORD_PREVIEW_BACKGROUND] = value
            }
        }

    /** Null clears the key; see [setGestureWordPreviewBackground]. */
    suspend fun setGestureWordPreviewTextColor(value: Long?) =
        editPrefs {
            if (value == null) {
                it.remove(GESTURE_WORD_PREVIEW_TEXT_COLOR)
            } else {
                it[GESTURE_WORD_PREVIEW_TEXT_COLOR] = value
            }
        }

    suspend fun setGestureStripPreviewOnly(value: Boolean) =
        editPrefs { it[GESTURE_STRIP_PREVIEW_ONLY] = value }

    suspend fun setGestureVocabulary(value: GlideVocabulary) =
        editPrefs { it[GESTURE_VOCABULARY] = value.name }

    /**
     * Leaving [GlideSandbox.AUTOMATIC] puts its ladder back at the bottom (#316).
     * Otherwise a rung the user once accepted outlives the setting: picking
     * "Every word" and then "Automatic" again would land straight back on
     * "Only my words", with nothing on screen to say so.
     */
    suspend fun setGestureSandbox(value: GlideSandbox) {
        var leftAutomatic = false
        editPrefs {
            leftAutomatic = it[GESTURE_SANDBOX] == GlideSandbox.AUTOMATIC.name &&
                value != GlideSandbox.AUTOMATIC
            it[GESTURE_SANDBOX] = value.name
        }
        // A running keyboard resets its own copy on the same change.
        if (leftAutomatic) runCatching { File(context.filesDir, GLIDE_SANDBOX_FILE).delete() }
    }

    /**
     * The rung [GlideSandbox.AUTOMATIC] has climbed to, as the option it
     * behaves like: [GlideSandbox.NORMAL] until the user accepts an offer on the
     * suggestion strip. Read off the keyboard's ladder file, whose `accepted`
     * holds a `GlideSandboxPolicy` name from :core:prediction.
     */
    suspend fun glideSandboxRung(): GlideSandbox = withContext(Dispatchers.IO) {
        val accepted = runCatching {
            val file = File(context.filesDir, GLIDE_SANDBOX_FILE)
            if (!file.exists()) return@runCatching null
            Json.parseToJsonElement(file.readText()).jsonObject["accepted"]?.jsonPrimitive?.content
        }.getOrNull()
        when (accepted) {
            "PREFER_LEARNED" -> GlideSandbox.PREFER_LEARNED
            "LEARNED_ONLY" -> GlideSandbox.LEARNED_ONLY
            else -> GlideSandbox.NORMAL
        }
    }

    suspend fun setGesturePreviewSteadiness(value: GlidePreviewSteadiness) =
        editPrefs { it[GESTURE_PREVIEW_STEADINESS] = value.name }

    suspend fun setGestureLookAhead(value: GlideLookAhead) =
        editPrefs { it[GESTURE_LOOK_AHEAD] = value.name }

    suspend fun setGestureCommitColor(value: GlideCommitColor) =
        editPrefs { it[GESTURE_COMMIT_COLOR] = value.name }

    suspend fun setGestureCommitColorScope(value: GlideCommitColorScope) =
        editPrefs { it[GESTURE_COMMIT_COLOR_SCOPE] = value.name }

    suspend fun setSpaceShortSwipe(value: SpaceSwipeAction) =
        editPrefs { it[SPACE_SHORT_SWIPE] = value.name }

    suspend fun setSpaceLongSwipe(value: SpaceSwipeAction) =
        editPrefs { it[SPACE_LONG_SWIPE] = value.name }

    suspend fun setSpacebarLanguageArrows(value: Boolean) =
        editPrefs { it[SPACEBAR_LANGUAGE_ARROWS] = value }

    suspend fun setSpacebarLabel(value: String) =
        editPrefs { it[SPACEBAR_LABEL] = value.trim() }

    suspend fun setSymbolsLongPressNumpad(value: Boolean) =
        editPrefs { it[SYMBOLS_LONGPRESS_NUMPAD] = value }

    suspend fun setEnterLongPressEmoji(value: Boolean) =
        editPrefs { it[ENTER_LONGPRESS_EMOJI] = value }

    suspend fun setSpaceSwipeDownHide(value: Boolean) =
        editPrefs { it[SPACE_SWIPE_DOWN_HIDE] = value }

    suspend fun setGlobeInOnePlace(value: Boolean) =
        editPrefs { it[GLOBE_IN_ONE_PLACE] = value }

    suspend fun setHintFlick(value: Boolean) =
        editPrefs { it[HINT_FLICK] = value }

    suspend fun setCapitalFlick(value: Boolean) =
        editPrefs { it[CAPITAL_FLICK] = value }

    suspend fun setGlobeTypingGuardMs(value: Int) =
        editPrefs { it[GLOBE_TYPING_GUARD_MS] = value.coerceIn(GlobeTypingGuardMsRange) }

    suspend fun setGlobeDragShortcuts(value: Boolean) =
        editPrefs { it[GLOBE_DRAG_SHORTCUTS] = value }

    suspend fun setBoardCornerTopDp(value: Int) =
        editPrefs { it[BOARD_CORNER_TOP] = value.coerceIn(BoardCornerRadiusRange) }

    suspend fun setBoardCornerBottomDp(value: Int) =
        editPrefs { it[BOARD_CORNER_BOTTOM] = value.coerceIn(BoardCornerRadiusRange) }

    suspend fun setBoardCorners(value: Set<BoardCorner>) =
        editPrefs { prefs -> prefs[BOARD_CORNERS] = value.mapTo(mutableSetOf()) { it.name } }

    suspend fun setSpaceCursor2d(value: Boolean) =
        editPrefs { it[SPACE_CURSOR_2D] = value }

    suspend fun setHintFontScale(value: Float) =
        editPrefs { it[HINT_FONT_SCALE] = value.coerceIn(0.5f, 2.0f) }

    suspend fun setHintOffsetDp(value: Int) =
        editPrefs { it[HINT_OFFSET] = value.coerceIn(0, 16) }

    suspend fun setTransliterationHints(value: TransliterationHintMode) =
        editPrefs { it[TRANSLITERATION_HINTS] = value.name }

    suspend fun setNumberRowShiftSymbols(value: Boolean) =
        editPrefs { it[NUMBER_ROW_SHIFT_SYMBOLS] = value }

    suspend fun setSmartHitDetection(value: Boolean) =
        editPrefs { it[SMART_HIT_DETECTION] = value }

    suspend fun setSkipTypedWord(value: Boolean) =
        editPrefs { it[SKIP_TYPED_WORD] = value }

    suspend fun setOctopusEnabled(value: Boolean) =
        editPrefs { it[OCTOPUS_ENABLED] = value }

    suspend fun setOctopusPlacement(value: OctopusPlacement) =
        editPrefs { it[OCTOPUS_PLACEMENT] = value.name }

    suspend fun setOctopusDensity(value: Int) = editPrefs {
        it[OCTOPUS_DENSITY] =
            value.coerceIn(OctopusSettings.MIN_DENSITY, OctopusSettings.MAX_DENSITY)
    }

    suspend fun setOctopusKinds(value: Set<OctopusKind>) = editPrefs {
        // Written even when empty: unticking everything is the user saying the
        // board should stay bare, not asking for the defaults back.
        it[OCTOPUS_KINDS] = value.joinToString("\n") { kind -> kind.name }
    }

    /** @see OctopusSettings.duringGlide */
    suspend fun setOctopusDuringGlide(value: OctopusDuringGlide) =
        editPrefs { it[OCTOPUS_DURING_GLIDE] = value.name }

    suspend fun setOctopusFlickCommits(value: Boolean) =
        editPrefs { it[OCTOPUS_FLICK_COMMITS] = value }

    suspend fun setOctopusTapCommits(value: Boolean) =
        editPrefs { it[OCTOPUS_TAP_COMMITS] = value }

    suspend fun setOctopusFlickSensitivity(value: OctopusFlickSensitivity) =
        editPrefs { it[OCTOPUS_FLICK_SENSITIVITY] = value.name }

    suspend fun setOctopusFontScale(value: Float) = editPrefs {
        it[OCTOPUS_FONT_SCALE] = value.coerceIn(
            OctopusSettings.FONT_SCALE_RANGE.start, OctopusSettings.FONT_SCALE_RANGE.endInclusive,
        )
    }

    suspend fun setOctopusSuppressHints(value: Boolean) =
        editPrefs { it[OCTOPUS_SUPPRESS_HINTS] = value }

    suspend fun setOctopusLongPressKeys(value: Boolean) =
        editPrefs { it[OCTOPUS_LONG_PRESS_KEYS] = value }

    suspend fun setOctopusWordsPerKey(value: Int) =
        editPrefs { it[OCTOPUS_WORDS_PER_KEY] = value.coerceIn(OctopusSettings.WORDS_PER_KEY_RANGE) }

    suspend fun setAutopilotStrength(value: Int) =
        editPrefs { it[AUTOPILOT_STRENGTH] = value.coerceIn(1, 10) }

    suspend fun setAutopilotShowEffect(value: Boolean) =
        editPrefs { it[AUTOPILOT_SHOW_EFFECT] = value }

    suspend fun setAutopilotOutline(value: Boolean) =
        editPrefs { it[AUTOPILOT_OUTLINE] = value }

    suspend fun setAutopilotVisualScale(value: Float) =
        editPrefs { it[AUTOPILOT_VISUAL_SCALE] = value.coerceIn(1f, 3f) }

    suspend fun setShiftEnterNewline(value: Boolean) =
        editPrefs { it[SHIFT_ENTER_NEWLINE] = value }

    // ---- power saving ----

    suspend fun setPowerSavingManual(value: Boolean) =
        editPrefs { it[PS_MANUAL] = value }

    suspend fun setPowerSavingTrigger(value: PowerSavingTrigger) =
        editPrefs { it[PS_TRIGGER] = value.name }

    /**
     * Clamped to 5..50: below 5 the phone is about to die anyway, and above 50
     * the keyboard would spend most of its life in a reduced mode the user
     * would read as broken rather than as thrifty.
     */
    suspend fun setPowerSavingBatteryPercent(value: Int) =
        editPrefs { it[PS_BATTERY_PERCENT] = value.coerceIn(5, 50) }

    suspend fun setPowerSavingOffWhileCharging(value: Boolean) =
        editPrefs { it[PS_OFF_WHILE_CHARGING] = value }

    suspend fun setPowerSavingDropHaptics(value: Boolean) =
        editPrefs { it[PS_DROP_HAPTICS] = value }

    suspend fun setPowerSavingDropKeySound(value: Boolean) =
        editPrefs { it[PS_DROP_KEY_SOUND] = value }

    suspend fun setPowerSavingDropAnimations(value: Boolean) =
        editPrefs { it[PS_DROP_ANIMATIONS] = value }

    suspend fun setPowerSavingDropGlideTrail(value: Boolean) =
        editPrefs { it[PS_DROP_GLIDE_TRAIL] = value }

    suspend fun setPowerSavingDropKeyPopup(value: Boolean) =
        editPrefs { it[PS_DROP_KEY_POPUP] = value }

    suspend fun setPowerSavingDropGestureTyping(value: Boolean) =
        editPrefs { it[PS_DROP_GESTURE_TYPING] = value }

    suspend fun setPowerSavingDropEmojiPrediction(value: Boolean) =
        editPrefs { it[PS_DROP_EMOJI_PREDICTION] = value }

    suspend fun setPowerSavingDropSmartChips(value: Boolean) =
        editPrefs { it[PS_DROP_SMART_CHIPS] = value }

    suspend fun setPowerSavingDropBackgroundNetwork(value: Boolean) =
        editPrefs { it[PS_DROP_BACKGROUND_NETWORK] = value }

    suspend fun setPowerSavingDropScreenshotWatch(value: Boolean) =
        editPrefs { it[PS_DROP_SCREENSHOT_WATCH] = value }

    suspend fun setPowerSavingDropOnDeviceModels(value: Boolean) =
        editPrefs { it[PS_DROP_ON_DEVICE_MODELS] = value }

    suspend fun setPowerSavingDropTypingStats(value: Boolean) =
        editPrefs { it[PS_DROP_TYPING_STATS] = value }

    suspend fun setPowerSavingDropMediaPin(value: Boolean) =
        editPrefs { it[PS_DROP_MEDIA_PIN] = value }

    // ---- data saving ----

    suspend fun setDataSaverManual(value: Boolean) =
        editPrefs { it[DS_MANUAL] = value }

    suspend fun setDataSaverTrigger(value: DataSaverTrigger) =
        editPrefs { it[DS_TRIGGER] = value.name }

    suspend fun setDataSaverLinkPreviews(value: MeteredPolicy) =
        editPrefs { it[DS_LINK_PREVIEWS] = value.name }

    suspend fun setDataSaverDictionaryLookup(value: MeteredPolicy) =
        editPrefs { it[DS_DICTIONARY_LOOKUP] = value.name }

    suspend fun setDataSaverPhotoBackgrounds(value: MeteredPolicy) =
        editPrefs { it[DS_PHOTO_BACKGROUNDS] = value.name }

    suspend fun setDataSaverWeatherChip(value: MeteredPolicy) =
        editPrefs { it[DS_WEATHER_CHIP] = value.name }

    suspend fun setDataSaverVocabAudio(value: MeteredPolicy) =
        editPrefs { it[DS_VOCAB_AUDIO] = value.name }

    suspend fun setDataSaverCurrencyRates(value: MeteredPolicy) =
        editPrefs { it[DS_CURRENCY_RATES] = value.name }

    suspend fun setDataSaverAddonRefresh(value: MeteredPolicy) =
        editPrefs { it[DS_ADDON_REFRESH] = value.name }

    suspend fun setDataSaverMediaSearch(value: MeteredPolicy) =
        editPrefs { it[DS_MEDIA_SEARCH] = value.name }

    suspend fun setDataSaverWebSearch(value: MeteredPolicy) =
        editPrefs { it[DS_WEB_SEARCH] = value.name }

    suspend fun setDataSaverAnimatedEmoji(value: MeteredPolicy) =
        editPrefs { it[DS_ANIMATED_EMOJI] = value.name }

    suspend fun setDataSaverDownloads(value: MeteredPolicy) =
        editPrefs { it[DS_DOWNLOADS] = value.name }

    suspend fun setDataSaverCloudAi(value: MeteredPolicy) =
        editPrefs { it[DS_CLOUD_AI] = value.name }

    suspend fun setDataSaverCloudVoice(value: MeteredPolicy) =
        editPrefs { it[DS_CLOUD_VOICE] = value.name }

    /**
     * Picks [value] as [langId]'s numeral system. [NumeralSystem.AUTO] drops the
     * entry, so the language falls back to its own default and the map stays
     * compact.
     */
    suspend fun setNumeralSystemForLanguage(langId: String, value: NumeralSystem) =
        editPrefs { prefs ->
            val current = prefs[NUMERAL_SYSTEM_BY_LANG]?.let { decodeNumeralSystems(it) }.orEmpty()
            val next =
                if (value == NumeralSystem.AUTO) current - langId else current + (langId to value)
            if (next == current) return@editPrefs
            prefs[NUMERAL_SYSTEM_BY_LANG] = encodeNumeralSystems(next)
        }

    suspend fun setSpacebarDisplay(value: SpacebarDisplay) =
        editPrefs { it[SPACEBAR_DISPLAY] = value.name }

    suspend fun setLanguagePickerStyle(value: LanguagePickerStyle) =
        editPrefs { it[LANGUAGE_PICKER_STYLE] = value.name }

    suspend fun setSpaceHoldPickerForLongRing(value: Boolean) =
        editPrefs { it[SPACE_HOLD_PICKER_FOR_LONG_RING] = value }

    suspend fun setNumeralCommitScope(value: NumeralCommitScope) =
        editPrefs { it[NUMERAL_COMMIT_SCOPE] = value.name }

    suspend fun setBackspaceSwipeDelete(value: Boolean) =
        editPrefs { it[BACKSPACE_SWIPE_DELETE] = value }

    suspend fun setHardwareKeyboardInput(value: Boolean) =
        editPrefs { it[HARDWARE_KEYBOARD_INPUT] = value }

    suspend fun setHwShortcutsEnabled(value: Boolean) =
        editPrefs { it[HW_SHORTCUTS_ENABLED] = value }

    suspend fun setHwPanelNavigation(value: Boolean) =
        editPrefs { it[HW_PANEL_NAVIGATION] = value }

    suspend fun setHwDpadKeyNavigation(value: Boolean) =
        editPrefs { it[HW_DPAD_KEY_NAVIGATION] = value }

    suspend fun setHwEscClosesPanel(value: Boolean) =
        editPrefs { it[HW_ESC_CLOSES_PANEL] = value }

    suspend fun setHwSuggestionHotkeys(value: SuggestionHotkeyMode) =
        editPrefs { it[HW_SUGGESTION_HOTKEYS] = value.name }

    suspend fun setHwSuggestionHintsAlways(value: Boolean) =
        editPrefs { it[HW_SUGGESTION_HINTS_ALWAYS] = value }

    suspend fun setHwToolbarDigitChord(value: Boolean) =
        editPrefs { it[HW_TOOLBAR_DIGIT_CHORD] = value }

    suspend fun setHwMacShortcuts(value: Boolean) =
        editPrefs { it[HW_MAC_SHORTCUTS] = value }

    suspend fun setHwLanguageSwitchChord(value: Boolean) =
        editPrefs { it[HW_LANGUAGE_SWITCH_CHORD] = value }

    suspend fun setHwHintModifierWords(value: Boolean) =
        editPrefs { it[HW_HINT_MODIFIER_WORDS] = value }

    /** Clamped, so a corrupt or out-of-range value cannot strand the badges on screen. */
    suspend fun setHwPickerTimeoutMs(value: Int) =
        editPrefs { it[HW_PICKER_TIMEOUT_MS] = value.coerceIn(PickerTimeoutRange) }

    suspend fun setHwAutoShowUi(value: Boolean) =
        editPrefs { it[HW_AUTO_SHOW_UI] = value }

    /** Stores the leader in its canonical text form; junk is refused, not persisted. */
    suspend fun setHwLeader(value: String) {
        val canonical = parseLeader(value)?.let(::formatLeader) ?: return
        editPrefs { it[HW_LEADER] = canonical }
    }

    /** The whole table at once — the shortcut editor's save and its reset button. */
    suspend fun setHwToolLetters(map: Map<Char, ToolbarTool>) =
        editPrefs { it[HW_TOOL_LETTERS] = encodeToolLetters(map) }

    /**
     * Binds one letter, or unbinds it when [tool] is null. Read-modify-write in a
     * single edit so two rows saved at once cannot lose each other, and it keeps
     * the table unambiguous in both directions: one letter opens one tool, and
     * one tool answers to one letter.
     */
    suspend fun setHwToolLetter(letter: Char, tool: ToolbarTool?) =
        editPrefs { prefs ->
            val current = prefs[HW_TOOL_LETTERS]?.let(::decodeToolLetters) ?: DefaultToolLetters
            val next = current.toMutableMap()
            next.remove(letter.uppercaseChar())
            if (tool != null) {
                next.entries.removeAll { it.value == tool }
                next[letter.uppercaseChar()] = tool
            }
            prefs[HW_TOOL_LETTERS] = encodeToolLetters(next)
        }

    suspend fun setVolumeCursor(value: Boolean) =
        editPrefs { it[VOLUME_CURSOR] = value }

    suspend fun setVolumeCursorMediaAware(value: Boolean) =
        editPrefs { it[VOLUME_CURSOR_MEDIA_AWARE] = value }

    suspend fun setGlobeAsEmoji(value: Boolean) =
        editPrefs { it[GLOBE_AS_EMOJI] = value }

    suspend fun setShowGlobeKey(value: Boolean) =
        editPrefs { it[SHOW_GLOBE_KEY] = value }

    suspend fun setGlobeRecentOrder(value: Boolean) =
        editPrefs { it[GLOBE_RECENT_ORDER] = value }

    suspend fun setOsLanguageSwitcher(value: Boolean) =
        editPrefs { it[OS_LANGUAGE_SWITCHER] = value }

    suspend fun setSubtypeAppNameFirst(value: Boolean) =
        editPrefs { it[SUBTYPE_APP_NAME_FIRST] = value }

    suspend fun setRememberLayoutPerApp(value: Boolean) =
        editPrefs { it[PER_APP_LANGUAGE_ENABLED] = value }

    /** Records [layoutId] as the last explicitly-picked layout for [packageName]. */
    suspend fun setAppLayout(packageName: String, layoutId: String) =
        editPrefs { prefs ->
            val current = prefs[PER_APP_LAYOUT_MAP]?.let { decodePerAppLayouts(it) }.orEmpty()
            if (current[packageName] == layoutId) return@editPrefs
            prefs[PER_APP_LAYOUT_MAP] = encodePerAppLayouts(current + (packageName to layoutId))
        }

    suspend fun setOnboardingDone(value: Boolean) =
        editPrefs { it[ONBOARDING_DONE] = value }

    suspend fun setPersonaLanguages(value: PersonaLanguages) =
        editPrefs { it[ONBOARDING_PERSONA_LANGUAGES] = value.name }

    suspend fun setPersonaDepth(value: PersonaDepth) =
        editPrefs { it[ONBOARDING_PERSONA_DEPTH] = value.name }

    suspend fun setPersonaPrivacy(value: PersonaPrivacy) =
        editPrefs { it[ONBOARDING_PERSONA_PRIVACY] = value.name }

    suspend fun setThemeGalleryStyle(value: ThemeGalleryStyle) =
        editPrefs { it[THEME_GALLERY_STYLE] = value.name }

    /** Remembers a settings fold as open or closed; see [AppUiSettings.advancedOpen]. */
    suspend fun setAdvancedFoldOpen(key: String, open: Boolean) = editPrefs {
        val now = it[ADVANCED_OPEN] ?: emptySet()
        it[ADVANCED_OPEN] = if (open) now + key else now - key
    }

    suspend fun setDefaultWordlistSize(value: DictionaryCatalog.DictionarySize) =
        editPrefs { it[DEFAULT_WORDLIST_SIZE] = value.name }

    /** See [AppUiSettings.dictionarySort]. */
    suspend fun setDictionarySort(value: DictionarySort) =
        editPrefs { it[DICTIONARY_SORT] = value.name }

    /** See [AppUiSettings.rowIcons]. */
    suspend fun setSettingsRowIcons(value: Boolean) =
        editPrefs { it[SETTINGS_ROW_ICONS] = value }

    /** See [AppUiSettings.screenTransitions]. */
    suspend fun setSettingsScreenTransitions(value: Boolean) =
        editPrefs { it[SETTINGS_SCREEN_TRANSITIONS] = value }

    suspend fun setWeatherRefreshMinutes(value: Int) =
        editPrefs { it[WEATHER_REFRESH_MINUTES] = value.coerceIn(1, 180) }

    suspend fun setWikiLinkLimit(value: Int) =
        editPrefs { it[WIKI_LINK_LIMIT] = value.coerceIn(50, 500) }

    suspend fun setQrMaxChars(value: Int) =
        editPrefs { it[QR_MAX_CHARS] = value.coerceIn(500, 4_000) }

    /** Blank restores the built-in pool; duplicates and whitespace are dropped. */
    suspend fun setPasswordSymbols(value: String) = editPrefs {
        it[PASSWORD_SYMBOLS] = value.filterNot { c -> c.isWhitespace() }
            .toCharArray().distinct().joinToString("")
    }

    suspend fun setSymbolRowHeightDp(value: Int) = editPrefs {
        it[SYMBOL_ROW_HEIGHT] = value.coerceIn(SymbolRowHeightRange.first, SymbolRowHeightRange.last)
    }

    suspend fun setSymbolRowLines(value: Int) = editPrefs {
        it[SYMBOL_ROW_LINES] = value.coerceIn(SymbolRowLinesRange.first, SymbolRowLinesRange.last)
    }

    suspend fun setSymbolRowScroll(value: SymbolRowScroll) =
        editPrefs { it[SYMBOL_ROW_SCROLL] = value.name }

    suspend fun setManualModeDuration(value: ManualModeDuration) =
        editPrefs { it[MANUAL_MODE_DURATION] = value.name }

    suspend fun setDictionaryBarEnabled(value: Boolean) =
        editPrefs { it[DICTIONARY_BAR_ENABLED] = value }

    /** The bar's language filter: a language id, or null for every language. */
    suspend fun setDictionaryBarFilter(langId: String?) =
        editPrefs { it[DICTIONARY_BAR_FILTER] = langId.orEmpty() }

    /** Turns cluster-aware backspace on or off for one language. */
    suspend fun setConjunctBackspace(languageId: String, value: Boolean) =
        editPrefs { prefs ->
            val current = conjunctLanguagesFromPrefs(prefs, emptyList())
            val next = if (value) current + languageId else current - languageId
            if (next == current) return@editPrefs
            // Always written, even when empty: an empty string is how "the user
            // has been here and turned everything off" is told apart from "never
            // touched", which is what stops the old global switch reviving.
            prefs[CONJUNCT_BACKSPACE_LANGUAGES] = next.joinToString(",")
        }

    /**
     * Which languages delete a whole cluster per backspace.
     *
     * Installs from before this was per language carry a single boolean. On it
     * applied to every cluster-forming language they had enabled, so that is
     * what it becomes — [enabledLanguages] is the set to spread it across, and
     * is empty when the caller is a writer that only needs the current value.
     */
    private fun conjunctLanguagesFromPrefs(
        p: Preferences,
        enabledLanguages: List<LanguageDef>,
    ): Set<String> {
        p[CONJUNCT_BACKSPACE_LANGUAGES]?.let { raw ->
            return raw.split(',').filter { it.isNotEmpty() }.toSet()
        }
        if (p[CONJUNCT_BACKSPACE] != true) return emptySet()
        return enabledLanguages
            .filter { ScriptRegistry[it.script].composer == ComposerType.INDIC_CLUSTER }
            .mapTo(mutableSetOf()) { it.id }
    }

    suspend fun setPinyinFuzzy(value: Boolean) =
        editPrefs { it[PINYIN_FUZZY] = value }

    suspend fun setPinyinFuzzyPair(id: String, on: Boolean) = editPrefs { p ->
        val current = p[PINYIN_FUZZY_PAIRS] ?: PinyinFuzzy.ALL_PAIRS
        p[PINYIN_FUZZY_PAIRS] = if (on) current + id else current - id
    }

    suspend fun resetPinyinFuzzyPairs() =
        editPrefs { it.remove(PINYIN_FUZZY_PAIRS) }

    suspend fun setPinyinDoublePinyin(value: DoublePinyinScheme) =
        editPrefs { it[PINYIN_DOUBLE_PINYIN] = value.name }

    suspend fun setCjkTraditionalOutput(value: Boolean) =
        editPrefs { it[CJK_TRADITIONAL_OUTPUT] = value }

    suspend fun setJyutpingLazy(value: Boolean) =
        editPrefs { it[JYUTPING_LAZY] = value }

    suspend fun setKanaLooseMarks(value: Boolean) =
        editPrefs { it[KANA_LOOSE_MARKS] = value }

    suspend fun setFullWidthSpace(languageId: String, value: Boolean) = editPrefs { p ->
        val current = p[FULL_WIDTH_SPACE_LANGUAGES] ?: emptySet()
        p[FULL_WIDTH_SPACE_LANGUAGES] = if (value) current + languageId else current - languageId
    }

    suspend fun setCjkHanRegion(value: HanVariant.HanRegion) =
        editPrefs { it[CJK_HAN_REGION] = value.name }

    suspend fun setOneHandedMode(value: OneHandedMode) =
        editPrefs { it[ONE_HANDED_MODE] = value.name }

    suspend fun setOneHandedWidthPercent(landscape: Boolean, value: Int) =
        editPrefs {
            it[oneHandedWidthKey(landscape)] =
                value.coerceIn(ONE_HANDED_WIDTH_MIN, ONE_HANDED_WIDTH_MAX)
        }

    suspend fun setOneHandedHeightScale(landscape: Boolean, value: Int) =
        editPrefs {
            it[oneHandedHeightScaleKey(landscape)] =
                value.coerceIn(ONE_HANDED_HEIGHT_SCALE_MIN, ONE_HANDED_HEIGHT_SCALE_MAX)
        }

    suspend fun setOneHandedSide(landscape: Boolean, value: OneHandedSide) =
        editPrefs { it[oneHandedSideKey(landscape)] = value.name }

    /**
     * Reads one orientation's one-handed profile. A missing dock side falls
     * back to the legacy global [ONE_HANDED_MODE] so users who had picked
     * LEFT/RIGHT before this feature keep that side as their default.
     */
    private fun readOneHandedProfile(
        p: Preferences,
        landscape: Boolean,
        default: OneHandedProfile,
    ): OneHandedProfile {
        val legacySide = p[ONE_HANDED_MODE]
            ?.let { runCatching { OneHandedMode.valueOf(it) }.getOrNull() }
            ?.let { OneHandedSide.of(it) }
        val side = p[oneHandedSideKey(landscape)]
            ?.let { runCatching { OneHandedSide.valueOf(it) }.getOrNull() }
            ?: legacySide ?: default.side
        return OneHandedProfile(
            widthPercent = p[oneHandedWidthKey(landscape)] ?: default.widthPercent,
            heightScale = p[oneHandedHeightScaleKey(landscape)] ?: default.heightScale,
            side = side,
        )
    }

    suspend fun setLearnFromTyping(value: Boolean) =
        editPrefs { it[LEARN_FROM_TYPING] = value }

    suspend fun setAddWordsToSystemDictionary(value: Boolean) =
        editPrefs { it[ADD_WORDS_TO_SYSTEM_DICTIONARY] = value }

    suspend fun setClipboardHistory(value: Boolean) =
        editPrefs { it[CLIPBOARD_HISTORY] = value }

    suspend fun setClipboardExpiryHours(value: Int) =
        editPrefs { it[CLIPBOARD_EXPIRY_HOURS] = value.coerceIn(0, 24 * 7) }

    /** Floor of 5: a cap below that turns history into a one-clip buffer. */
    suspend fun setClipboardMaxItems(value: Int) =
        editPrefs { it[CLIPBOARD_MAX_ITEMS] = value.coerceIn(5, 500) }

    suspend fun setClipboardSensitiveHandling(value: SensitiveClipHandling) =
        editPrefs { it[CLIPBOARD_SENSITIVE_HANDLING] = value.name }

    suspend fun setClipboardDetectSensitive(value: Boolean) =
        editPrefs { it[CLIPBOARD_DETECT_SENSITIVE] = value }

    suspend fun setClipboardSensitiveExpiryMinutes(value: Int) =
        editPrefs { it[CLIPBOARD_SENSITIVE_EXPIRY_MINUTES] = value.coerceIn(1, 120) }

    suspend fun setClipboardLinkPreviews(value: Boolean) =
        editPrefs { it[CLIPBOARD_LINK_PREVIEWS] = value }

    suspend fun setClipboardTrackSource(value: Boolean) =
        editPrefs { it[CLIPBOARD_TRACK_SOURCE] = value }

    suspend fun setClipboardSuggestRecent(value: Boolean) =
        editPrefs { it[CLIPBOARD_SUGGEST_RECENT] = value }

    /**
     * Writes the new key and clears the boolean it replaced, so the migration
     * in the reader cannot resurrect a stale answer after a reset.
     */
    suspend fun setClipboardCopiedCodeChip(value: CopiedCodeChip) = editPrefs {
        it[CLIPBOARD_COPIED_CODE_CHIP] = value.name
        it.remove(CLIPBOARD_SUGGEST_CODES_IN_CODE_FIELDS)
    }

    suspend fun setPunctuationSuggestions(value: Boolean) =
        editPrefs { it[PUNCTUATION_SUGGESTIONS] = value }

    /** Blank stores blank; the read side falls back to the shipped marks. */
    suspend fun setPunctuationChips(value: String) =
        editPrefs { it[PUNCTUATION_CHIPS] = value.filterNot { c -> c.isWhitespace() }.take(12) }

    suspend fun setSuggestionSlotCount(value: Int) =
        editPrefs { it[SUGGESTION_SLOT_COUNT] = value.coerceIn(2, 6) }

    suspend fun setSuggestionScrollable(value: Boolean) =
        editPrefs { it[SUGGESTION_SCROLLABLE] = value }

    /** Null clears the key, so the strip follows the theme again (#90). */
    suspend fun setSuggestionPrimaryColor(value: Long?) =
        editPrefs {
            if (value == null) it.remove(SUGGESTION_PRIMARY_COLOR) else it[SUGGESTION_PRIMARY_COLOR] = value
        }

    /** Padding either side of a suggestion word, in dp; see [SuggestionStripSettings.chipPadding]. */
    suspend fun setSuggestionChipPadding(value: Int) =
        editPrefs { it[SUGGESTION_CHIP_PADDING] = value.coerceIn(0, 24) }

    suspend fun setClipboardPinnedLast(value: Boolean) =
        editPrefs { it[CLIPBOARD_PINNED_LAST] = value }

    suspend fun setClipboardSearch(value: Boolean) =
        editPrefs { it[CLIPBOARD_SEARCH] = value }

    suspend fun setClipboardUserScreenshots(value: Boolean) =
        editPrefs { it[CLIPBOARD_USER_SCREENSHOTS] = value }

    suspend fun setClipboardClearAfterPasswordPaste(value: Boolean) =
        editPrefs { it[CLIPBOARD_CLEAR_AFTER_PASSWORD_PASTE] = value }

    suspend fun setClipboardDetectEntities(value: Boolean) =
        editPrefs { it[CLIPBOARD_DETECT_ENTITIES] = value }

    /**
     * Adds a phone-number mask. Invalid masks are dropped here rather than at
     * the detector, which has no way to tell the user about one.
     *
     * Both edits start from [seededPhoneFormats] and not from the empty set,
     * because until one of them runs the key is absent and the list on screen
     * is the seeded one. Reading it as empty would quietly drop the mask the
     * user can see while they were adding a second country.
     */
    suspend fun addClipboardPhoneFormat(mask: String) {
        val normalized = PhoneFormats.canonical(mask) ?: return
        editPrefs {
            it[CLIPBOARD_PHONE_FORMATS] =
                (it[CLIPBOARD_PHONE_FORMATS] ?: seededPhoneFormats()) + normalized
        }
    }

    /** Deletes a phone-number mask. Deleting the last one detects every number again. */
    suspend fun removeClipboardPhoneFormat(mask: String) =
        editPrefs {
            it[CLIPBOARD_PHONE_FORMATS] =
                (it[CLIPBOARD_PHONE_FORMATS] ?: seededPhoneFormats()) - mask
        }

    suspend fun setClipboardFullBleed(value: Boolean) =
        editPrefs { it[CLIPBOARD_FULL_BLEED] = value }

    suspend fun setClipboardView(value: ClipboardView) =
        editPrefs { it[CLIPBOARD_VIEW] = value.name }

    suspend fun setClipboardShowNumbers(value: Boolean) =
        editPrefs { it[CLIPBOARD_SHOW_NUMBERS] = value }

    suspend fun setClipboardUndoDelete(value: Boolean) =
        editPrefs { it[CLIPBOARD_UNDO_DELETE] = value }

    suspend fun setClipboardSwipeToDelete(value: Boolean) =
        editPrefs { it[CLIPBOARD_SWIPE_TO_DELETE] = value }

    suspend fun setClipboardPreviewLines(value: Int) =
        editPrefs { it[CLIPBOARD_PREVIEW_LINES] = value.coerceIn(ClipPreviewLinesRange) }

    suspend fun setClipboardGridColumns(value: Int) =
        editPrefs { it[CLIPBOARD_GRID_COLUMNS] = value.coerceIn(ClipGridColumnsRange) }

    suspend fun setClipboardTimeLabel(value: ClipTimeLabel) =
        editPrefs { it[CLIPBOARD_TIME_LABEL] = value.name }

    suspend fun setClipboardMaxTextChars(value: Int) =
        editPrefs { it[CLIPBOARD_MAX_TEXT_CHARS] = value.coerceAtLeast(0) }

    suspend fun setOtpChipEnabled(value: Boolean) =
        editPrefs { it[OTP_CHIP_ENABLED] = value }

    suspend fun setOtpCodeFieldsOnly(value: Boolean) =
        editPrefs { it[OTP_CODE_FIELDS_ONLY] = value }

    suspend fun setOtpExpiryMinutes(value: Int) =
        editPrefs { it[OTP_EXPIRY_MINUTES] = value.coerceIn(1, 10) }

    suspend fun setOtpDismissNotification(value: Boolean) =
        editPrefs { it[OTP_DISMISS_NOTIFICATION] = value }

    suspend fun setOtpPerDigitEntry(value: Boolean) =
        editPrefs { it[OTP_PER_DIGIT_ENTRY] = value }

    suspend fun setAutoBackupEnabled(value: Boolean) =
        editPrefs { it[AUTO_BACKUP_ENABLED] = value }

    /**
     * Points the automatic backup at a folder, or clears it.
     *
     * Taking the persistable grant is the caller's job — it has the picker
     * result and this does not.
     */
    suspend fun setAutoBackupFolderUri(value: String) = editPrefs {
        it[AUTO_BACKUP_FOLDER_URI] = value
        // A new destination has no history here, and the old failure was about
        // the old folder.
        it[AUTO_BACKUP_LAST_ERROR] = ""
        it[AUTO_BACKUP_LAST_RUN_AT] = 0L
    }

    /** Switches destination, and forgets the old one's run record with it. */
    suspend fun setAutoBackupDestination(value: BackupDestination) = editPrefs {
        it[AUTO_BACKUP_DESTINATION] = value.id
        it[AUTO_BACKUP_LAST_ERROR] = ""
        it[AUTO_BACKUP_LAST_RUN_AT] = 0L
    }

    /**
     * Sets the WebDAV collection to upload into.
     *
     * A trailing slash is added because every path below is joined onto this,
     * and a server handed a doubled or missing slash answers 404 rather than
     * anything useful.
     */
    suspend fun setAutoBackupWebDavUrl(value: String) = editPrefs {
        val trimmed = value.trim()
        it[AUTO_BACKUP_WEBDAV_URL] =
            if (trimmed.isEmpty() || trimmed.endsWith("/")) trimmed else "$trimmed/"
        it[AUTO_BACKUP_LAST_ERROR] = ""
    }

    suspend fun setAutoBackupWebDavUser(value: String) =
        editPrefs { it[AUTO_BACKUP_WEBDAV_USER] = value.trim() }

    suspend fun setAutoBackupWebDavPassword(value: String) =
        editPrefs { it[AUTO_BACKUP_WEBDAV_PASSWORD] = value }

    /** Replaces the whole S3 configuration; the screen edits it as one thing. */
    suspend fun setAutoBackupS3(value: S3Config) = editPrefs {
        it[AUTO_BACKUP_S3_ENDPOINT] = value.endpoint.trim()
        it[AUTO_BACKUP_S3_REGION] = S3Sink.normalizeRegion(value.region)
        it[AUTO_BACKUP_S3_BUCKET] = value.bucket.trim()
        it[AUTO_BACKUP_S3_PREFIX] = value.prefix.trim().trim('/')
        it[AUTO_BACKUP_S3_KEY_ID] = value.accessKeyId.trim()
        it[AUTO_BACKUP_S3_SECRET] = value.secretAccessKey
        it[AUTO_BACKUP_S3_PATH_STYLE] = value.pathStyle
        it[AUTO_BACKUP_LAST_ERROR] = ""
    }

    /** Replaces the whole FTP configuration. */
    suspend fun setAutoBackupFtp(value: FtpConfig) = editPrefs {
        it[AUTO_BACKUP_FTP_HOST] = value.host.trim()
        it[AUTO_BACKUP_FTP_PORT] = value.port.coerceIn(1, 65535)
        it[AUTO_BACKUP_FTP_USER] = value.user.trim()
        it[AUTO_BACKUP_FTP_PASSWORD] = value.password
        it[AUTO_BACKUP_FTP_PATH] = value.path.trim().trim('/')
        it[AUTO_BACKUP_FTP_SECURE] = value.secure
        it[AUTO_BACKUP_LAST_ERROR] = ""
    }

    /** Stores or clears the Dropbox grant. Empty means signed out. */
    suspend fun setAutoBackupDropboxToken(value: String) = editPrefs {
        it[AUTO_BACKUP_DROPBOX_TOKEN] = value
        it[AUTO_BACKUP_LAST_ERROR] = ""
    }

    /** Stores or clears the OneDrive grant. Empty means signed out. */
    suspend fun setAutoBackupOneDriveToken(value: String) = editPrefs {
        it[AUTO_BACKUP_ONEDRIVE_TOKEN] = value
        it[AUTO_BACKUP_LAST_ERROR] = ""
    }

    suspend fun setAutoBackupIntervalHours(value: Int) =
        editPrefs { it[AUTO_BACKUP_INTERVAL_HOURS] = value.coerceIn(1, 24 * 30) }

    suspend fun setAutoBackupKeep(value: Int) =
        editPrefs { it[AUTO_BACKUP_KEEP] = value.coerceIn(1, 50) }

    suspend fun setAutoBackupRequireUnmetered(value: Boolean) =
        editPrefs { it[AUTO_BACKUP_UNMETERED] = value }

    suspend fun setAutoBackupRequireCharging(value: Boolean) =
        editPrefs { it[AUTO_BACKUP_CHARGING] = value }

    /** Writes the key even when [value] is empty: an empty set is a choice. */
    suspend fun setAutoBackupSections(value: Set<ConfigBackup.Section>) =
        editPrefs { prefs -> prefs[AUTO_BACKUP_SECTIONS] = value.mapTo(HashSet()) { it.id } }

    suspend fun setAutoBackupIncludeSecrets(value: Boolean) =
        editPrefs { it[AUTO_BACKUP_INCLUDE_SECRETS] = value }

    suspend fun setAutoBackupEncrypt(value: Boolean) =
        editPrefs { it[AUTO_BACKUP_ENCRYPT] = value }

    /**
     * Sets or clears the passphrase, minting a salt the first time.
     *
     * The salt is per install and only ever used to write new files — every
     * file carries its own in its header — so replacing it cannot make an
     * existing backup unreadable.
     */
    suspend fun setAutoBackupPassphrase(value: String) = editPrefs { prefs ->
        prefs[AUTO_BACKUP_PASSPHRASE] = value
        if (value.isEmpty()) {
            prefs[AUTO_BACKUP_KDF_SALT] = ""
        } else if (prefs[AUTO_BACKUP_KDF_SALT].isNullOrEmpty()) {
            prefs[AUTO_BACKUP_KDF_SALT] =
                Base64.encodeToString(BackupCrypto.newSalt(), Base64.NO_WRAP)
        }
    }

    /** Records the outcome of a run. [error] is a `SinkError` name, or empty. */
    suspend fun setAutoBackupOutcome(ranAtMs: Long, error: String) = editPrefs {
        if (error.isEmpty()) it[AUTO_BACKUP_LAST_RUN_AT] = ranAtMs
        it[AUTO_BACKUP_LAST_ERROR] = error
    }

    /** The stored list, or the legacy single destination until one is written. */
    private fun currentLocations(prefs: Preferences): List<BackupLocation> =
        prefs[AUTO_BACKUP_LOCATIONS]?.let(BackupLocation::decodeList)
            ?: mapPreferences(prefs).autoBackup.locations

    /**
     * Adds [location], or replaces the one with its id. Trims what the user
     * typed the way the old per-field setters did, since a stray space in a
     * host name is a failure that looks like a wrong password.
     */
    suspend fun upsertBackupLocation(location: BackupLocation) = editPrefs { prefs ->
        val clean = location.copy(
            name = location.name.trim(),
            webDavUrl = location.webDavUrl.trim().let {
                if (it.isEmpty() || it.endsWith("/")) it else "$it/"
            },
            webDavUser = location.webDavUser.trim(),
            s3 = location.s3.copy(
                endpoint = location.s3.endpoint.trim(),
                region = S3Sink.normalizeRegion(location.s3.region),
                bucket = location.s3.bucket.trim(),
                prefix = location.s3.prefix.trim().trim('/'),
                accessKeyId = location.s3.accessKeyId.trim(),
            ),
            ftp = location.ftp.copy(
                host = location.ftp.host.trim(),
                port = location.ftp.port.coerceIn(1, 65535),
                user = location.ftp.user.trim(),
                path = location.ftp.path.trim().trim('/'),
            ),
        )
        val list = currentLocations(prefs)
        val next = if (list.any { it.id == clean.id }) {
            list.map { if (it.id == clean.id) clean else it }
        } else {
            list + clean
        }
        prefs[AUTO_BACKUP_LOCATIONS] = BackupLocation.encodeList(next)
    }

    /** Removes a location and its run record. */
    suspend fun removeBackupLocation(id: String) = editPrefs { prefs ->
        prefs[AUTO_BACKUP_LOCATIONS] =
            BackupLocation.encodeList(currentLocations(prefs).filterNot { it.id == id })
        val status = LocationStatus.decodeMap(prefs[AUTO_BACKUP_LOCATION_STATUS]) - id
        prefs[AUTO_BACKUP_LOCATION_STATUS] = LocationStatus.encodeMap(status)
    }

    /** Changes one location in place, if it still exists. */
    suspend fun updateBackupLocation(id: String, change: (BackupLocation) -> BackupLocation) {
        val current = settings.first().autoBackup.locations.firstOrNull { it.id == id } ?: return
        upsertBackupLocation(change(current))
    }

    /** Rewrites one location's run record. */
    suspend fun setLocationStatus(id: String, change: (LocationStatus) -> LocationStatus) = editPrefs { prefs ->
        val status = LocationStatus.decodeMap(prefs[AUTO_BACKUP_LOCATION_STATUS])
        prefs[AUTO_BACKUP_LOCATION_STATUS] =
            LocationStatus.encodeMap(status + (id to change(status[id] ?: LocationStatus())))
    }

    /** Writes the key even when [value] is empty: an empty set is a choice. */
    suspend fun setExportSections(value: Set<ConfigBackup.Section>) =
        editPrefs { prefs -> prefs[EXPORT_SECTIONS] = value.mapTo(HashSet()) { it.id } }

    suspend fun setBackupIncludeSecrets(value: Boolean) = editPrefs { it[AUTO_BACKUP_INCLUDE_KEYS] = value }

    suspend fun setSyncEnabled(value: Boolean) = editPrefs { it[SYNC_ENABLED] = value }

    suspend fun setSyncMode(value: SyncMode) = editPrefs { it[SYNC_MODE] = value.id }

    suspend fun setSyncIntervalHours(value: Int) =
        editPrefs { it[SYNC_INTERVAL_HOURS] = value.coerceIn(1, 24 * 30) }

    /**
     * The ticked sync locations: the stored set, or, before there was one,
     * what the single choice meant. An empty choice then meant "the first
     * usable location", and read as "none" it would quietly stop a sync that
     * had been running.
     */
    private fun syncLocationIds(prefs: Preferences, locations: List<BackupLocation>): Set<String> =
        prefs[SYNC_LOCATION_IDS]
            ?: prefs[SYNC_LOCATION_ID]?.takeIf { it.isNotEmpty() }?.let { setOf(it) }
            ?: if (prefs[SYNC_ENABLED] == true) {
                setOfNotNull(locations.firstOrNull { it.active }?.id)
            } else {
                emptySet()
            }

    /** Ticks or unticks one location for sync. */
    suspend fun setSyncLocation(id: String, on: Boolean) = editPrefs { prefs ->
        val current = syncLocationIds(prefs, currentLocations(prefs))
        prefs[SYNC_LOCATION_IDS] = if (on) current + id else current - id
    }

    /** Writes the key even when [value] is empty: an empty set is a choice. */
    suspend fun setSyncSections(value: Set<ConfigBackup.Section>) =
        editPrefs { prefs -> prefs[SYNC_SECTIONS] = value.mapTo(HashSet()) { it.id } }

    suspend fun setSyncIncludeSecrets(value: Boolean) = editPrefs { it[SYNC_INCLUDE_SECRETS] = value }

    /** Keeps one group of settings on this device, or lets it sync again. */
    suspend fun setSyncKeepLocal(group: com.wasimaster.wmkeyboard.core.settings.sync.SyncKeys.LocalGroup, on: Boolean) =
        editPrefs { prefs ->
            val current = prefs[SYNC_KEEP_LOCAL].orEmpty()
            prefs[SYNC_KEEP_LOCAL] = if (on) current + group.id else current - group.id
        }

    /**
     * Writes settings another device changed, and removes ones it reset.
     *
     * [put] is the typed `{ key: { type, value } }` map the settings section
     * uses. Unlike [importConfig] this can delete, because a sync carries
     * resets and a restore does not.
     */
    suspend fun applySyncedSettings(put: JsonObject, remove: Set<String>) {
        val (entries, _) = SettingsBackup.decodeSettings(put)
        editPrefs { prefs ->
            entries.forEach { prefs.put(it) }
            if (remove.isNotEmpty()) {
                prefs.asMap().keys.filter { it.name in remove }.forEach { prefs.remove(it) }
            }
        }
    }

    /**
     * A cheap fingerprint of every setting that syncs, for noticing a change
     * worth pushing. Not a hash of the whole store: the keyboard writes its
     * own counters and state all day, and those must not wake a sync.
     */
    fun syncFingerprint(
        includeSecrets: Boolean,
        keepLocal: Set<com.wasimaster.wmkeyboard.core.settings.sync.SyncKeys.LocalGroup>,
    ): Flow<Int> =
        context.dataStore.data.map { prefs ->
            prefs.asMap().entries
                .filter {
                    com.wasimaster.wmkeyboard.core.settings.sync.SyncKeys.syncable(it.key.name, includeSecrets, keepLocal)
                }
                .sumOf { (it.key.name to it.value.toString()).hashCode() }
        }.distinctUntilChanged()

    /** Records how a sync pass ended. [error] empty means it worked. */
    suspend fun setSyncOutcome(ranAtMs: Long, error: String) = editPrefs {
        if (error.isEmpty()) it[SYNC_LAST_RUN_AT] = ranAtMs
        it[SYNC_LAST_ERROR] = error
    }

    suspend fun setLongPressDelayMs(value: Int) =
        editPrefs { it[LONG_PRESS_DELAY] = value.coerceIn(100, 800) }

    suspend fun setDeleteRepeatIntervalMs(value: Int) =
        editPrefs { it[KEY_REPEAT_DELETE] = value.coerceIn(20, 200) }

    suspend fun setWordDeleteRepeatIntervalMs(value: Int) =
        editPrefs { it[KEY_REPEAT_WORD_DELETE] = value.coerceIn(60, 500) }

    suspend fun setSpaceRepeatIntervalMs(value: Int) =
        editPrefs { it[KEY_REPEAT_SPACE] = value.coerceIn(20, 200) }

    suspend fun setCustomKeyRepeatIntervalMs(value: Int) =
        editPrefs { it[KEY_REPEAT_CUSTOM] = value.coerceIn(20, 200) }

    suspend fun setKeyRepeatStartDelayMs(value: Int) =
        editPrefs { it[KEY_REPEAT_START_DELAY] = value.coerceIn(150, 800) }

    suspend fun setLongPressHints(value: Boolean) =
        editPrefs { it[LONG_PRESS_HINTS] = value }

    suspend fun setLongPressASelectAll(value: Boolean) =
        editPrefs { it[LONG_PRESS_A_SELECT_ALL] = value }

    suspend fun setLongPressCCopy(value: Boolean) =
        editPrefs { it[LONG_PRESS_C_COPY] = value }

    suspend fun setLongPressVPaste(value: Boolean) =
        editPrefs { it[LONG_PRESS_V_PASTE] = value }

    suspend fun setLongPressXCut(value: Boolean) =
        editPrefs { it[LONG_PRESS_X_CUT] = value }

    suspend fun setLongPressZUndo(value: Boolean) =
        editPrefs { it[LONG_PRESS_Z_UNDO] = value }

    suspend fun setLongPressYRedo(value: Boolean) =
        editPrefs { it[LONG_PRESS_Y_REDO] = value }

    /**
     * Rebinds the six hold-shortcut keys. Anything that is not six characters
     * is refused rather than stored, so a half-typed value cannot leave every
     * one of the six actions bound to nothing.
     */
    suspend fun setLongPressLetters(value: String) {
        if (value.length != DEFAULT_LONG_PRESS_LETTERS.length) return
        editPrefs { it[LONG_PRESS_LETTERS] = value }
    }

    suspend fun setLongPressActionFirst(value: Boolean) =
        editPrefs { it[LONG_PRESS_ACTION_FIRST] = value }

    suspend fun setEmojiToolbar(value: Boolean) =
        editPrefs { it[EMOJI_TOOLBAR] = value }

    suspend fun setColoredToolIcons(value: Boolean) =
        editPrefs { it[COLORED_TOOL_ICONS] = value }

    suspend fun setToolIconGradients(value: Boolean) =
        editPrefs { it[TOOL_ICON_GRADIENTS] = value }

    /** Override one tool's accent colour; a null [color] restores its default. */
    suspend fun setToolColor(tool: ToolbarTool, color: Long?) =
        editPrefs { prefs ->
            val current = decodeToolColors(prefs[TOOL_COLOR_OVERRIDES]).toMutableMap()
            if (color == null) current.remove(tool) else current[tool] = color
            prefs[TOOL_COLOR_OVERRIDES] = encodeToolColors(current)
        }

    /**
     * Override the far end of one tool's gradient; a null [color] goes back to
     * the end colour derived from the tool's near one.
     */
    suspend fun setToolColorEnd(tool: ToolbarTool, color: Long?) =
        editPrefs { prefs ->
            val current = decodeToolColors(prefs[TOOL_COLOR_END_OVERRIDES]).toMutableMap()
            if (color == null) current.remove(tool) else current[tool] = color
            prefs[TOOL_COLOR_END_OVERRIDES] = encodeToolColors(current)
        }

    /** Drop every per-tool colour override, restoring all built-in defaults. */
    suspend fun clearToolColors() =
        editPrefs {
            it.remove(TOOL_COLOR_OVERRIDES)
            it.remove(TOOL_COLOR_END_OVERRIDES)
        }

    /** Switch icon packs; a blank [packId] goes back to the built-in icons. */
    suspend fun setIconPack(packId: String) =
        editPrefs { it[ICON_PACK_ID] = packId }

    /** Override one slot's icon; a null [source] restores its default. */
    suspend fun setIconOverride(slot: String, source: String?) =
        editPrefs { prefs ->
            val current = IconOverrides.decode(prefs[ICON_OVERRIDES]).toMutableMap()
            if (source == null) current.remove(slot) else current[slot] = source
            prefs[ICON_OVERRIDES] = IconOverrides.encode(current)
        }

    /**
     * Drop every per-slot override *and* the active pack, so every icon is the
     * built-in one again. Uninstalling the packs themselves is separate — this
     * is "stop using them", not "delete them".
     */
    suspend fun clearIconOverrides() =
        editPrefs {
            it.remove(ICON_OVERRIDES)
            it.remove(ICON_PACK_ID)
        }

    /**
     * Forgets [packId] everywhere it is referenced, for when a pack is deleted:
     * the active pack falls back to the built-ins and any slot pinned to it
     * loses its override, rather than both silently resolving to nothing.
     */
    suspend fun forgetIconPack(packId: String) =
        editPrefs { prefs ->
            if (prefs[ICON_PACK_ID] == packId) prefs.remove(ICON_PACK_ID)
            val kept = IconOverrides.decode(prefs[ICON_OVERRIDES])
                .filterValues { it != IconOverrides.packSource(packId) }
            if (kept.isEmpty()) prefs.remove(ICON_OVERRIDES)
            else prefs[ICON_OVERRIDES] = IconOverrides.encode(kept)
        }

    suspend fun setEmojiTabMode(value: EmojiTabMode) =
        editPrefs { it[EMOJI_TAB_MODE] = value.name }

    suspend fun setEmojiClearRecentsButton(value: Boolean) =
        editPrefs { it[EMOJI_CLEAR_RECENTS_BUTTON] = value }

    suspend fun setEmojiLongPressName(value: Boolean) =
        editPrefs { it[EMOJI_LONG_PRESS_NAME] = value }

    suspend fun setEmojiPrediction(value: Boolean) =
        editPrefs { it[EMOJI_PREDICTION] = value }

    suspend fun setEmojiBarMode(value: EmojiBarMode) =
        editPrefs { it[EMOJI_BAR_MODE] = value.name }

    suspend fun setEmojiBarContent(value: EmojiBarContent) =
        editPrefs { it[EMOJI_BAR_CONTENT] = value.name }

    suspend fun setEmojiBarScrollable(value: Boolean) =
        editPrefs { it[EMOJI_BAR_SCROLLABLE] = value }

    suspend fun setEmojiBarCount(value: Int) =
        editPrefs { it[EMOJI_BAR_COUNT] = value.coerceIn(EmojiBarCountRange) }

    suspend fun setEmojiGridCellSize(value: Int) =
        editPrefs { it[EMOJI_GRID_CELL_SIZE] = value.coerceIn(EmojiGridCellSizeRange) }

    suspend fun setEmojiGridEmojiSize(value: Int) =
        editPrefs { it[EMOJI_GRID_EMOJI_SIZE] = value.coerceIn(EmojiGridEmojiSizeRange) }

    suspend fun setEmojiInsertMode(value: EmojiInsertMode) =
        editPrefs { it[EMOJI_INSERT_MODE] = value.name }

    suspend fun setEmojiDefaultSkinTone(value: EmojiSkinTone) =
        editPrefs { it[EMOJI_DEFAULT_SKIN_TONE] = value.name }

    suspend fun setEmojiToneOverrideByLastUsed(value: Boolean) =
        editPrefs { it[EMOJI_TONE_OVERRIDE_LAST_USED] = value }

    suspend fun setEmojiCloseAfterInsert(value: Boolean) =
        editPrefs { it[EMOJI_CLOSE_AFTER_INSERT] = value }

    suspend fun setHideUnrenderableEmoji(value: Boolean) =
        editPrefs { it[EMOJI_HIDE_UNRENDERABLE] = value }

    suspend fun setEmojiKaomojiTabs(value: Boolean) =
        editPrefs { it[EMOJI_KAOMOJI_TABS] = value }

    suspend fun setIncognito(value: Boolean) =
        editPrefs { it[INCOGNITO] = value }

    suspend fun setTranslateTargetLang(value: String) =
        editPrefs { it[TRANSLATE_TARGET_LANG] = value }

    suspend fun setTranslateEngine(value: TranslateEngine) =
        editPrefs { it[TRANSLATE_ENGINE] = value.name }

    suspend fun setTranslateDownloadedFirst(value: Boolean) =
        editPrefs { it[TRANSLATE_DOWNLOADED_FIRST] = value }

    suspend fun setTranslateOnlyDownloaded(value: Boolean) =
        editPrefs { it[TRANSLATE_ONLY_DOWNLOADED] = value }

    suspend fun setDeepLApiKey(value: String) =
        editPrefs { it[DEEPL_API_KEY] = value.trim() }

    suspend fun setDeepLEndpoint(value: String) =
        editPrefs { it[DEEPL_ENDPOINT] = value.trim() }

    suspend fun setDeepLTranslate(value: Boolean) =
        editPrefs { it[DEEPL_TRANSLATE] = value }

    suspend fun setDeepLWrite(value: Boolean) =
        editPrefs { it[DEEPL_WRITE] = value }

    suspend fun setDeepLWriteStyle(value: DeepLWriteStyle) =
        editPrefs { it[DEEPL_WRITE_STYLE] = value.name }

    suspend fun setGrammarDialect(value: GrammarDialect) =
        editPrefs { it[GRAMMAR_DIALECT] = value.name }

    /** Replaces the grammar filter; see [KeyboardSettings.grammarHiddenKinds]. */
    suspend fun setGrammarHiddenKinds(value: Set<GrammarLintKind>) =
        editPrefs { prefs -> prefs[GRAMMAR_HIDDEN_KINDS] = value.mapTo(mutableSetOf()) { it.name } }

    /** Shows or hides one grammar issue kind, leaving the rest of the filter alone. */
    suspend fun setGrammarKindShown(kind: GrammarLintKind, shown: Boolean) =
        editPrefs { prefs ->
            val now = prefs[GRAMMAR_HIDDEN_KINDS] ?: emptySet()
            prefs[GRAMMAR_HIDDEN_KINDS] = if (shown) now - kind.name else now + kind.name
        }

    /** Shows or hides every kind in one grammar category at once. */
    suspend fun setGrammarCategoryShown(category: GrammarCategory, shown: Boolean) =
        editPrefs { prefs ->
            val names = GrammarLintKind.of(category).mapTo(mutableSetOf()) { it.name }
            val now = prefs[GRAMMAR_HIDDEN_KINDS] ?: emptySet()
            prefs[GRAMMAR_HIDDEN_KINDS] = if (shown) now - names else now + names
        }

    suspend fun setSpellCheckerNoSuggestions(value: Boolean) =
        editPrefs { it[SPELL_CHECKER_NO_SUGGESTIONS] = value }

    suspend fun setTranslateApiKey(value: String) =
        editPrefs { it[TRANSLATE_API_KEY] = value.trim() }

    suspend fun setKlipyApiKey(value: String) =
        editPrefs { it[KLIPY_API_KEY] = value.trim() }

    suspend fun setBraveApiKey(value: String) =
        editPrefs { it[BRAVE_API_KEY] = value.trim() }

    suspend fun setGiphyApiKey(value: String) =
        editPrefs { it[GIPHY_API_KEY] = value.trim() }

    suspend fun setGifSourceMode(value: GifSourceMode) =
        editPrefs { it[GIF_SOURCE_MODE] = value.name }

    suspend fun setStickerSuggest(value: Boolean) =
        editPrefs { it[STICKER_SUGGEST] = value }

    suspend fun setStickerSuggestStyle(value: StickerSuggestStyle) =
        editPrefs { it[STICKER_SUGGEST_STYLE] = value.name }

    suspend fun setStickerSuggestTrigger(value: StickerTriggerAction) =
        editPrefs { it[STICKER_SUGGEST_TRIGGER] = value.name }

    suspend fun setGifContentFilter(value: GifContentFilter) =
        editPrefs { it[GIF_CONTENT_FILTER] = value.name }

    suspend fun setSearchSafe(value: Boolean) =
        editPrefs { it[SEARCH_SAFE] = value }

    suspend fun setGifResultLimit(value: Int) =
        editPrefs { it[GIF_RESULT_LIMIT] = value.coerceIn(6, 48) }

    suspend fun setSearchResultCount(value: Int) =
        editPrefs { it[SEARCH_RESULT_COUNT] = value.coerceIn(1, 10) }

    suspend fun setWikiLanguage(value: String) =
        editPrefs { it[WIKI_LANGUAGE] = value.trim().lowercase() }

    suspend fun setWikiLinksMarkdown(value: Boolean) =
        editPrefs { it[WIKI_LINKS_MARKDOWN] = value }

    /** Pushes one symbol to the front of the recents row (capped, deduped). */
    suspend fun addSymbolRecent(symbol: String) =
        editPrefs { prefs ->
            val current = prefs[SYMBOL_RECENTS]?.split('\t')?.filter { it.isNotEmpty() }
                .orEmpty()
            prefs[SYMBOL_RECENTS] =
                (listOf(symbol) + current.filter { it != symbol }).take(24).joinToString("\t")
        }

    suspend fun clearSymbolRecents() =
        editPrefs { it.remove(SYMBOL_RECENTS) }

    suspend fun setSymbolRowEnabled(value: Boolean) =
        editPrefs { it[SYMBOL_ROW_ENABLED] = value }

    /** The sets the row's picker offers; an empty pick falls back to defaults. */
    suspend fun setSymbolRowSetIds(ids: List<String>) =
        editPrefs { it[SYMBOL_ROW_SETS] = ids.distinct().joinToString("\t") }

    suspend fun setSymbolRowActiveSet(id: String) =
        editPrefs { it[SYMBOL_ROW_ACTIVE_SET] = id }

    suspend fun setFancyStyle(id: String) =
        editPrefs { it[FANCY_STYLE] = id }

    /** The style the Fancy tool turns on with; null (stored empty) follows the strip. */
    suspend fun setFancyToolStyle(id: String?) =
        editPrefs { it[FANCY_TOOL_STYLE] = id.orEmpty() }

    suspend fun setFancyToolKeepsLanguage(value: Boolean) =
        editPrefs { it[FANCY_TOOL_KEEPS_LANGUAGE] = value }

    suspend fun setFancyToolAutoOff(value: Boolean) =
        editPrefs { it[FANCY_TOOL_AUTO_OFF] = value }

    /** The secondary layout the Secondary layout tool shows; null (stored empty) is the first one. */
    suspend fun setCustomLayoutToolLayout(id: String?) =
        editPrefs { it[CUSTOM_LAYOUT_TOOL] = id.orEmpty() }

    /**
     * The style an install that predates the single fancy layout should keep:
     * the first old per-style layout id still sitting in the raw preferences
     * ("asset_fancy_fraktur" → "fraktur"). Read-time only, like the Bengali
     * font fallback — nothing is rewritten, so a downgrade finds its layout
     * ids untouched.
     */
    private fun legacyFancyStyle(p: Preferences): String? =
        (
            p[ENABLED_LAYOUT_IDS]?.split(',').orEmpty() +
                listOfNotNull(p[ACTIVE_LAYOUT_ID])
            )
            .firstOrNull { it.startsWith("asset_fancy_") }
            ?.removePrefix("asset_fancy_")

    /**
     * Adds the set or replaces the stored set with the same id. The popups are
     * cleaned here rather than trusted from the caller, so a stored set never
     * carries a popup for an entry it no longer has.
     */
    suspend fun upsertSymbolSet(set: SymbolSet) =
        editPrefs { prefs ->
            val current = prefs[CUSTOM_SYMBOL_SETS]?.let { SymbolSetCodec.decodeList(it) }
                .orEmpty()
            val clean = set.copy(popups = sanitizeSymbolPopups(set.chars, set.popups))
            val next = current.filter { it.id != set.id } + clean
            prefs[CUSTOM_SYMBOL_SETS] = SymbolSetCodec.encodeList(next)
        }

    /**
     * Takes one entry out of a set, from a hold on the symbol row (#323).
     *
     * [index] is where the row drew [entry]; it is only trusted while it still
     * names that entry, so a set edited in the meantime loses the first copy
     * of the text instead of whatever moved into the slot. A shipped set is
     * edited the way its editor edits it, as an override under the same id,
     * which the editor's Reset undoes. The last entry of a set stays: an empty
     * set is a row with nothing on it.
     */
    suspend fun removeSymbolSetEntry(setId: String, index: Int, entry: String) =
        editPrefs { prefs ->
            val current = prefs[CUSTOM_SYMBOL_SETS]?.let { SymbolSetCodec.decodeList(it) }
                .orEmpty()
            val set = current.firstOrNull { it.id == setId } ?: BuiltInSymbolSets.byId(setId)
                ?: return@editPrefs
            val at = index.takeIf { set.chars.getOrNull(it) == entry } ?: set.chars.indexOf(entry)
            if (at < 0 || set.chars.size <= 1) return@editPrefs
            val chars = set.chars.toMutableList().apply { removeAt(at) }
            val edited = set.copy(chars = chars, popups = sanitizeSymbolPopups(chars, set.popups))
            // In place, so a custom set keeps its spot in the picker.
            val next = if (current.any { it.id == setId }) {
                current.map { if (it.id == setId) edited else it }
            } else {
                current + edited
            }
            prefs[CUSTOM_SYMBOL_SETS] = SymbolSetCodec.encodeList(next)
        }

    /** Deletes a custom set and drops every reference to it. */
    suspend fun deleteSymbolSet(id: String) =
        editPrefs { prefs ->
            val current = prefs[CUSTOM_SYMBOL_SETS]?.let { SymbolSetCodec.decodeList(it) }
                .orEmpty()
            prefs[CUSTOM_SYMBOL_SETS] = SymbolSetCodec.encodeList(current.filter { it.id != id })
            // Deleting an edited built-in only drops the override — the
            // shipped set comes back, so every reference to it stays valid.
            if (BuiltInSymbolSets.byId(id) != null) return@editPrefs
            prefs[SYMBOL_ROW_SETS]?.let { stored ->
                prefs[SYMBOL_ROW_SETS] = stored.split('\t').filter { it.isNotEmpty() && it != id }
                    .joinToString("\t")
            }
            if (prefs[SYMBOL_ROW_ACTIVE_SET] == id) prefs.remove(SYMBOL_ROW_ACTIVE_SET)
            // Modes referencing the set inherit the global sets again.
            prefs[KEYBOARD_MODES]?.let { stored ->
                val modes = KeyboardModeCodec.decodeList(stored).map { mode ->
                    val kept = mode.symbolSetIds?.filter { it != id }
                    mode.copy(symbolSetIds = kept?.ifEmpty { null })
                }
                prefs[KEYBOARD_MODES] = KeyboardModeCodec.encodeList(modes)
            }
        }

    suspend fun setBarOrder(rows: List<BarRow>) =
        editPrefs {
            it[BAR_ORDER] = sanitizeBarOrder(rows).joinToString(",") { row -> row.name }
        }

    suspend fun setEmojiFullBleed(value: Boolean) =
        editPrefs { it[EMOJI_FULL_BLEED] = value }

    suspend fun setMediaFullBleed(value: Boolean) =
        editPrefs { it[MEDIA_FULL_BLEED] = value }

    suspend fun setModeToolOrderEdits(value: Boolean) =
        editPrefs { it[MODE_TOOL_ORDER_EDITS] = value }

    suspend fun setModeToolOrderHintSeen(value: Boolean) =
        editPrefs { it[MODE_TOOL_ORDER_HINT] = value }

    /**
     * Rewrites one mode's pinned toolbar, so a drag made while that mode is
     * active lands where it will actually be read back from. Pinning into a
     * mode that was appending its tools switches it to replacing them: the
     * dragged arrangement is the whole bar the user just laid out, and
     * re-appending would shuffle it behind the global pins.
     */
    suspend fun setModeToolbarTools(modeId: String, tools: List<ToolbarTool>) =
        editPrefs { prefs ->
            val modes = prefs[KEYBOARD_MODES]?.let { KeyboardModeCodec.decodeList(it) }
                ?: DefaultKeyboardModes
            prefs[KEYBOARD_MODES] = KeyboardModeCodec.encodeList(
                modes.map { mode ->
                    if (mode.id == modeId) {
                        mode.copy(toolbarTools = tools.distinct(), toolbarToolsAppend = false)
                    } else {
                        mode
                    }
                }
            )
        }

    /**
     * Rewrites one mode's toolbox order. A mode stores only the tools it
     * floats to the front, so the full dragged order is stored as-is and the
     * global order stays the tiebreaker for anything the mode never names.
     */
    suspend fun setModeToolboxOrder(modeId: String, order: List<ToolbarTool>) =
        editPrefs { prefs ->
            val modes = prefs[KEYBOARD_MODES]?.let { KeyboardModeCodec.decodeList(it) }
                ?: DefaultKeyboardModes
            prefs[KEYBOARD_MODES] = KeyboardModeCodec.encodeList(
                modes.map { mode ->
                    if (mode.id == modeId) mode.copy(toolboxOrder = order.distinct()) else mode
                }
            )
        }

    /**
     * Adds default modes introduced after this install first ran. Fresh
     * installs get the whole list from [DefaultKeyboardModes]; an upgrade has
     * a stored list frozen at whatever shipped back then, so the new modes
     * would never appear. [MODE_SEED_VERSION] records how far the stored list
     * has been topped up — bump it whenever [DefaultKeyboardModes] grows, and
     * a mode the user deleted stays deleted because its version is already
     * covered. Idempotent; safe to call on every start.
     */
    suspend fun seedNewDefaultModes() =
        editPrefs { prefs ->
            val seeded = prefs[MODE_SEED_VERSION] ?: 0
            if (seeded >= CurrentModeSeedVersion) return@editPrefs
            prefs[MODE_SEED_VERSION] = CurrentModeSeedVersion
            // No stored list at all: the read path already falls back to the
            // full defaults, so there is nothing to top up.
            var stored = prefs[KEYBOARD_MODES]?.let { KeyboardModeCodec.decodeList(it) }
                ?: return@editPrefs
            if (seeded < 2) {
                val have = stored.map { it.id }.toSet()
                stored = stored + DefaultKeyboardModes.filter {
                    it.id !in have && it.id in ModesAddedInSeedVersion2
                }
            }
            // Version 2 runs first so a chat mode it just added already
            // carries the version-3 apps — the bound-set check in the
            // top-up then leaves it alone.
            if (seeded < 3) {
                stored = topUpModeApps(stored, ModeAppsAddedInSeedVersion3)
                stored = topUpModeFields(stored, ModeFieldsAddedInSeedVersion3)
            }
            prefs[KEYBOARD_MODES] = KeyboardModeCodec.encodeList(stored)
        }

    /** Adds the mode or replaces the stored mode with the same id. */
    suspend fun upsertKeyboardMode(mode: KeyboardMode) =
        editPrefs { prefs ->
            val current = prefs[KEYBOARD_MODES]?.let { KeyboardModeCodec.decodeList(it) }
                ?: DefaultKeyboardModes
            val next =
                if (current.any { it.id == mode.id }) current.map { if (it.id == mode.id) mode else it }
                else current + mode
            prefs[KEYBOARD_MODES] = KeyboardModeCodec.encodeList(next)
        }

    suspend fun deleteKeyboardMode(id: String) =
        editPrefs { prefs ->
            val current = prefs[KEYBOARD_MODES]?.let { KeyboardModeCodec.decodeList(it) }
                ?: DefaultKeyboardModes
            prefs[KEYBOARD_MODES] = KeyboardModeCodec.encodeList(current.filter { it.id != id })
        }

    /**
     * Restores a built-in mode to the configuration it ships with (its entry
     * in [DefaultKeyboardModes]), discarding the user's edits to it. A no-op
     * for an id that was never a built-in — a user-created mode has no shipped
     * default to fall back to.
     */
    suspend fun resetKeyboardModeToDefault(id: String) {
        val default = DefaultKeyboardModes.firstOrNull { it.id == id } ?: return
        upsertKeyboardMode(default)
    }

    suspend fun setSmartSuggestions(value: Boolean) =
        editPrefs { it[SMART_SUGGESTIONS] = value }

    suspend fun setSmartCalc(value: Boolean) =
        editPrefs { it[SMART_CALC] = value }

    suspend fun setSmartCurrency(value: Boolean) =
        editPrefs { it[SMART_CURRENCY] = value }

    suspend fun setSmartUnits(value: Boolean) =
        editPrefs { it[SMART_UNITS] = value }

    suspend fun setSmartToolKeywords(value: Boolean) =
        editPrefs { it[SMART_TOOL_KEYWORDS] = value }

    suspend fun setModesEnabled(value: Boolean) =
        editPrefs { it[MODES_ENABLED] = value }

    suspend fun setSmartChipDates(value: Boolean) =
        editPrefs { it[SMART_CHIP_DATES] = value }

    suspend fun setSmartChipWeather(value: Boolean) =
        editPrefs { it[SMART_CHIP_WEATHER] = value }

    suspend fun setSmartChipLookups(value: Boolean) =
        editPrefs { it[SMART_CHIP_LOOKUPS] = value }

    suspend fun setSmartChipIntents(value: Boolean) =
        editPrefs { it[SMART_CHIP_INTENTS] = value }

    suspend fun setSmartChipGifs(value: Boolean) =
        editPrefs { it[SMART_CHIP_GIFS] = value }

    suspend fun setSmartChipNumbers(value: Boolean) =
        editPrefs { it[SMART_CHIP_NUMBERS] = value }

    /** Which grouping a number chip offers; [NumberGrouping.AUTO] follows the language. */
    suspend fun setSmartChipNumberGrouping(value: NumberGrouping) =
        editPrefs { it[SMART_CHIP_NUMBER_GROUPING] = value.name }

    suspend fun setSelectionMacrosEnabled(value: Boolean) =
        editPrefs { it[SELECTION_MACROS_ENABLED] = value }

    suspend fun setSelectionMacroPlacement(value: SelectionMacroPlacement) =
        editPrefs { it[SELECTION_MACROS_PLACEMENT] = value.name }

    suspend fun setSelectionMacroDetectEntities(value: Boolean) =
        editPrefs { it[SELECTION_MACROS_DETECT] = value }

    /**
     * Stamps the current list version, writing the shipped lists first when
     * the stored ones predate it.
     *
     * Every list setter runs this before its own write. Without it, a user
     * whose first touch after an update is a reorder would stamp the version
     * and thereby bring their pre-update on-list back to life, which the
     * version rule exists to prevent.
     */
    private fun MutablePreferences.adoptMacroListVersion() {
        if (this[SELECTION_MACROS_LIST_VERSION] == SelectionMacros.LIST_VERSION) return
        this[SELECTION_MACROS_ON] = SelectionMacroCodec.encodeMacros(SelectionMacros.defaultMacros)
        this[SELECTION_MACROS_ORDER] = SelectionMacroCodec.encodeOrder(SelectionMacros.defaultOrder)
        this[SELECTION_MACROS_LIST_VERSION] = SelectionMacros.LIST_VERSION
    }

    /** Replaces the whole on-list; only [SelectionMacros.configurable] is kept. */
    suspend fun setSelectionMacros(value: Set<SelectionMacro>) =
        editPrefs {
            it.adoptMacroListVersion()
            it[SELECTION_MACROS_ON] = SelectionMacroCodec.encodeMacros(value)
        }

    /** One macro's switch, for the row that draws it. */
    suspend fun setSelectionMacroEnabled(macro: SelectionMacro, on: Boolean) =
        editPrefs {
            it.adoptMacroListVersion()
            val current = SelectionMacroCodec.decodeMacros(it[SELECTION_MACROS_LIST_VERSION], it[SELECTION_MACROS_ON])
            it[SELECTION_MACROS_ON] = SelectionMacroCodec.encodeMacros(if (on) current + macro else current - macro)
        }

    suspend fun setSelectionMacroOrder(value: List<SelectionMacro>) =
        editPrefs {
            it.adoptMacroListVersion()
            it[SELECTION_MACROS_ORDER] = SelectionMacroCodec.encodeOrder(value)
        }

    suspend fun setSelectionMacroAiActions(ids: List<String>) =
        editPrefs { it[SELECTION_MACROS_AI_ACTIONS] = AiActionCodec.encodeIds(ids) }

    suspend fun setSelectionMacroTimeZones(ids: List<String>) =
        editPrefs { it[SELECTION_MACROS_TIME_ZONES] = AiActionCodec.encodeIds(ids) }

    /** Replaces one tool's trigger words; an empty list silences that tool. */
    suspend fun setToolKeywords(tool: ToolbarTool, words: List<String>) =
        editPrefs {
            it[TOOL_KEYWORDS] = SmartSuggest.withKeywords(it[TOOL_KEYWORDS].orEmpty(), tool, words)
        }

    /** Whether one tool's trigger words have to match the typed capitals. */
    suspend fun setToolKeywordCaseSensitive(tool: ToolbarTool, sensitive: Boolean) =
        editPrefs {
            it[TOOL_KEYWORD_CASE] =
                SmartSuggest.withCaseSensitive(it[TOOL_KEYWORD_CASE].orEmpty(), tool, sensitive)
        }

    suspend fun setCalcDegrees(value: Boolean) =
        editPrefs { it[CALC_DEGREES] = value }

    suspend fun setCalcPhoneLayout(value: Boolean) =
        editPrefs { it[CALC_PHONE_LAYOUT] = value }

    suspend fun setCalcPrecision(value: Int) =
        editPrefs { it[CALC_PRECISION] = value.coerceIn(0, 12) }

    suspend fun setCurrencyPair(from: String, to: String) =
        editPrefs {
            it[CURRENCY_FROM] = from.trim().uppercase()
            it[CURRENCY_TO] = to.trim().uppercase()
        }

    suspend fun setPwLength(value: Int) =
        editPrefs { it[PW_LENGTH] = value.coerceIn(4, 64) }

    suspend fun setPwUppercase(value: Boolean) =
        editPrefs { it[PW_UPPERCASE] = value }

    suspend fun setPwDigits(value: Boolean) =
        editPrefs { it[PW_DIGITS] = value }

    suspend fun setPwSymbols(value: Boolean) =
        editPrefs { it[PW_SYMBOLS] = value }

    suspend fun setPwExcludeAmbiguous(value: Boolean) =
        editPrefs { it[PW_EXCLUDE_AMBIGUOUS] = value }

    suspend fun setPwPassphraseMode(value: Boolean) =
        editPrefs { it[PW_PASSPHRASE_MODE] = value }

    suspend fun setPpWordCount(value: Int) =
        editPrefs { it[PP_WORD_COUNT] = value.coerceIn(2, 10) }

    suspend fun setPpSeparator(value: String) =
        editPrefs { it[PP_SEPARATOR] = value.take(3) }

    suspend fun setPpCapitalize(value: Boolean) =
        editPrefs { it[PP_CAPITALIZE] = value }

    suspend fun setPpIncludeDigit(value: Boolean) =
        editPrefs { it[PP_INCLUDE_DIGIT] = value }

    suspend fun setTypingTestMode(value: TypingTestMode) =
        editPrefs { it[TT_MODE] = value.name }

    suspend fun setTypingTestDuration(value: Int) =
        editPrefs { it[TT_DURATION] = value.coerceIn(5, 600) }

    suspend fun setTypingTestWordCount(value: Int) =
        editPrefs { it[TT_WORD_COUNT] = value.coerceIn(5, 500) }

    suspend fun setTypingTestPunctuation(value: Boolean) =
        editPrefs { it[TT_PUNCTUATION] = value }

    suspend fun setTypingTestNumbers(value: Boolean) =
        editPrefs { it[TT_NUMBERS] = value }

    suspend fun setTypingTestGlide(value: Boolean) =
        editPrefs { it[TT_GLIDE] = value }

    suspend fun setTypingTestSuggestions(value: Boolean) =
        editPrefs { it[TT_SUGGESTIONS] = value }

    /**
     * Files a finished run: appends it to the history, bumps the counter,
     * stores a new personal best when [bests] is non-null (the caller has
     * already checked whether the record fell), and folds the run's newly
     * earned achievement badges into the unlocked set.
     */
    suspend fun recordTypingResult(history: String, bests: String?, achievements: Set<String> = emptySet()) =
        editPrefs { p ->
            p[TT_HISTORY] = history
            if (bests != null) p[TT_BESTS] = bests
            p[TT_COMPLETED] = (p[TT_COMPLETED] ?: 0) + 1
            if (achievements.isNotEmpty()) {
                val unlocked = TypingAchievements.decode(p[TT_ACHIEVEMENTS].orEmpty())
                p[TT_ACHIEVEMENTS] = TypingAchievements.encode(unlocked + achievements)
            }
        }

    /** Wipes the personal bests, the score history and the badges. */
    suspend fun clearTypingStats() =
        editPrefs {
            it[TT_BESTS] = ""
            it[TT_HISTORY] = ""
            it[TT_COMPLETED] = 0
            it[TT_ACHIEVEMENTS] = ""
        }

    suspend fun setQrSizePx(value: Int) =
        editPrefs { it[QR_SIZE_PX] = value.coerceIn(256, 2048) }

    suspend fun setQrEcc(value: QrEccLevel) =
        editPrefs { it[QR_ECC] = value.name }

    suspend fun setAiProvider(value: AiProvider) =
        editPrefs { it[AI_PROVIDER] = value.name }

    suspend fun setAiAnthropicKey(value: String) =
        editPrefs { it[AI_ANTHROPIC_KEY] = value.trim() }

    suspend fun setAiOpenAiKey(value: String) =
        editPrefs { it[AI_OPENAI_KEY] = value.trim() }

    suspend fun setAiGeminiKey(value: String) =
        editPrefs { it[AI_GEMINI_KEY] = value.trim() }

    suspend fun setAiAnthropicModel(value: String) =
        editPrefs { it[AI_ANTHROPIC_MODEL] = value.trim() }

    suspend fun setAiOpenAiModel(value: String) =
        editPrefs { it[AI_OPENAI_MODEL] = value.trim() }

    suspend fun setAiGeminiModel(value: String) =
        editPrefs { it[AI_GEMINI_MODEL] = value.trim() }

    suspend fun setAiOllamaUrl(value: String) =
        editPrefs { it[AI_OLLAMA_URL] = value.trim().trimEnd('/') }

    suspend fun setAiOllamaModel(value: String) =
        editPrefs { it[AI_OLLAMA_MODEL] = value.trim() }

    suspend fun setAiLmStudioUrl(value: String) =
        editPrefs { it[AI_LM_STUDIO_URL] = value.trim().trimEnd('/') }

    suspend fun setAiLmStudioModel(value: String) =
        editPrefs { it[AI_LM_STUDIO_MODEL] = value.trim() }

    suspend fun setAiXaiKey(value: String) =
        editPrefs { it[AI_XAI_KEY] = value.trim() }

    suspend fun setAiXaiModel(value: String) =
        editPrefs { it[AI_XAI_MODEL] = value.trim() }

    suspend fun setAiDeepSeekKey(value: String) =
        editPrefs { it[AI_DEEPSEEK_KEY] = value.trim() }

    suspend fun setAiDeepSeekModel(value: String) =
        editPrefs { it[AI_DEEPSEEK_MODEL] = value.trim() }

    suspend fun setAiCompatibleUrl(value: String) =
        editPrefs { it[AI_COMPATIBLE_URL] = value.trim().trimEnd('/') }

    suspend fun setAiCompatibleKey(value: String) =
        editPrefs { it[AI_COMPATIBLE_KEY] = value.trim() }

    suspend fun setAiCompatibleModel(value: String) =
        editPrefs { it[AI_COMPATIBLE_MODEL] = value.trim() }

    /** `0` keeps the ceiling out of the request; see [AiSettings.maxTokens]. */
    suspend fun setAiMaxTokens(value: Int) =
        editPrefs { it[AI_MAX_TOKENS] = if (value <= 0) 0 else value.coerceIn(64, 262_144) }

    /** `0` leaves the window to the model; see [AiSettings.localContextTokens]. */
    suspend fun setAiLocalContextTokens(value: Int) =
        editPrefs {
            it[AI_LOCAL_CONTEXT_TOKENS] = if (value <= 0) 0 else value.coerceIn(512, 32_768)
        }

    suspend fun setAiTranslateTo(value: String) =
        editPrefs { it[AI_TRANSLATE_TO] = value.trim() }

    /**
     * Saves an action: the user's own, or their edit of a shipped one. An edit
     * is stored under the shipped id and shadows it, so the shipped version is
     * still there to go back to.
     */
    suspend fun upsertAiAction(action: AiActionSpec) =
        editPrefs { prefs ->
            val current = AiActionCodec.decodeList(prefs[AI_CUSTOM_ACTIONS].orEmpty())
            val next = current.filter { it.id != action.id } + action
            prefs[AI_CUSTOM_ACTIONS] = AiActionCodec.encodeList(next)
            // The old key for this action, if any, would otherwise be folded
            // back in on the next read and undo the edit.
            clearLegacyAiPrompt(prefs, action.id)
        }

    /**
     * Drops the stored spec for [id].
     *
     * For a shipped action that *is* the reset, and its id stays valid, so the
     * order and the turned-off list are left alone. For the user's own action
     * it is a delete, and the id has to go from both lists or it would sit
     * there for good.
     */
    suspend fun deleteAiAction(id: String) =
        editPrefs { prefs ->
            val current = AiActionCodec.decodeList(prefs[AI_CUSTOM_ACTIONS].orEmpty())
            prefs[AI_CUSTOM_ACTIONS] = AiActionCodec.encodeList(current.filter { it.id != id })
            clearLegacyAiPrompt(prefs, id)
            if (BuiltInAiActions.isBuiltIn(id)) return@editPrefs
            val order = AiActionCodec.decodeIds(prefs[AI_ACTION_ORDER].orEmpty())
            if (id in order) prefs[AI_ACTION_ORDER] = AiActionCodec.encodeIds(order - id)
            val off = AiActionCodec.decodeIds(prefs[AI_ACTIONS_OFF].orEmpty())
            if (id in off) prefs[AI_ACTIONS_OFF] = AiActionCodec.encodeIds(off - id)
        }

    suspend fun setAiActionOrder(ids: List<String>) =
        editPrefs { it[AI_ACTION_ORDER] = AiActionCodec.encodeIds(ids) }

    /** Turns one action off, or back on. A shipped action is never deleted. */
    suspend fun setAiActionHidden(id: String, hidden: Boolean) =
        editPrefs { prefs ->
            val off = AiActionCodec.decodeIds(prefs[AI_ACTIONS_OFF].orEmpty())
            val next = if (hidden) (off + id).distinct() else off - id
            prefs[AI_ACTIONS_OFF] = AiActionCodec.encodeIds(next)
        }

    /**
     * Removes the pre-list prompt override for [id]. Called whenever a spec is
     * written for that action, because the two describe the same thing and the
     * merge on read deliberately prefers the stored spec.
     */
    private fun clearLegacyAiPrompt(prefs: MutablePreferences, id: String) {
        val key = when (id) {
            BuiltInAiActions.REWRITE_ID -> AI_PROMPT_REWRITE
            BuiltInAiActions.SUMMARIZE_ID -> AI_PROMPT_SUMMARIZE
            BuiltInAiActions.TRANSLATE_ID -> AI_PROMPT_TRANSLATE
            BuiltInAiActions.IMPROVE_ID -> AI_PROMPT_IMPROVE
            BuiltInAiActions.FIX_GRAMMAR_ID -> AI_PROMPT_FIX_GRAMMAR
            BuiltInAiActions.EXPLAIN_ID -> AI_PROMPT_EXPLAIN
            BuiltInAiActions.CONTINUE_ID -> AI_PROMPT_CONTINUE
            else -> return
        }
        prefs.remove(key)
    }

    suspend fun setAiLocalModelId(value: String) =
        editPrefs { it[AI_LOCAL_MODEL_ID] = value }

    suspend fun setAiLocalBackend(value: LocalLlmBackend) =
        editPrefs { it[AI_LOCAL_BACKEND] = value.name }

    suspend fun setHfToken(value: String) =
        editPrefs { it[HF_TOKEN] = value.trim() }

    suspend fun setAiShowThinking(value: Boolean) =
        editPrefs { it[AI_SHOW_THINKING] = value }

    suspend fun setAiPanelModelPicker(value: Boolean) =
        editPrefs { it[AI_PANEL_MODEL_PICKER] = value }

    suspend fun setAiDiffView(value: Boolean) =
        editPrefs { it[AI_DIFF_VIEW] = value }

    suspend fun setAiDiffOpensFirst(value: Boolean) =
        editPrefs { it[AI_DIFF_OPENS_FIRST] = value }

    suspend fun setAiHistoryEnabled(value: Boolean) =
        editPrefs { it[AI_HISTORY_ENABLED] = value }

    suspend fun setAiHistoryMax(value: Int) =
        editPrefs {
            it[AI_HISTORY_MAX] =
                value.coerceIn(AiHistoryStore.MIN_MAX_ITEMS, AiHistoryStore.MAX_ITEMS_CEILING)
        }
}
