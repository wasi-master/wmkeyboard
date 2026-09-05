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
import com.wasimaster.wmkeyboard.core.settings.sink.S3Sink
import com.wasimaster.wmkeyboard.core.addons.AddonStore
import com.wasimaster.wmkeyboard.core.clipboard.ClipboardStore
import com.wasimaster.wmkeyboard.core.clipboard.PhoneFormats
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.icons.IconOverrides
import com.wasimaster.wmkeyboard.core.icons.IconPackStore
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryCatalog
import com.wasimaster.wmkeyboard.core.tools.QrCodeGen
import com.wasimaster.wmkeyboard.core.input.composer.DoublePinyinScheme
import com.wasimaster.wmkeyboard.core.input.composer.HanVariant
import com.wasimaster.wmkeyboard.core.input.composer.PinyinFuzzy
import com.wasimaster.wmkeyboard.core.prediction.CustomDictionaries
import com.wasimaster.wmkeyboard.core.snippets.MultiExpandMode
import com.wasimaster.wmkeyboard.core.layout.AssetLayouts
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.LayoutCodec
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.repair
import com.wasimaster.wmkeyboard.core.layout.resolveLayoutSelection
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.layout.script
import com.wasimaster.wmkeyboard.core.tools.AltCalendar
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.random.Random
import com.wasimaster.wmkeyboard.settings.R
import com.wasimaster.wmkeyboard.common.R as CommonR

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
     * Height of the bubble in the mode that [onKey] selects. The two styles want
     * very different numbers — an on-key bubble is measured from the bottom of
     * the key it covers, so most of its height is spent climbing back out from
     * under the finger, while a floating bubble already starts above the key and
     * only has to hold one character. The repository resolves this field from a
     * separate stored key per mode ([floatingHeightDp] is the default for the
     * floating one), so each style keeps its own tuned value and a slider drag
     * in one mode does not resize the other.
     */
    val heightDp: Int = 110,
    /** Default [heightDp] when [onKey] is off; see there. */
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
)

/**
 * Hold-to-repeat cadence, per key.
 *
 * Backspace and space are the only keys that repeat under a held finger, and
 * they are held for opposite reasons: a fast backspace clears a line in one
 * hold, while a fast spacebar runs away and has to be undone. So each keeps its
 * own interval rather than sharing one "key repeat" slider.
 *
 * Nested to keep [KeyboardSettings]'s top-level field count under the JVM
 * `copy$default` ceiling; the DataStore keys stay flat.
 */
data class KeyRepeatSettings(
    /** Backspace and forward-delete, including the panel backspaces. */
    val deleteMs: Int = 50,
    /**
     * The spacebar, at half the cadence of backspace. A held space overshoots
     * in a way a held backspace does not: the extra spaces are invisible until
     * the word after them lands in the wrong place.
     */
    val spaceMs: Int = 100,
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
 * English dialect the offline grammar tool lints against. Ordinals are the
 * contract with the native Harper library — append only, never reorder.
 */
enum class GrammarDialect(@StringRes val labelRes: Int) {
    AMERICAN(R.string.core_settings_grammar_dialect_american_label),
    BRITISH(R.string.core_settings_grammar_dialect_british_label),
    CANADIAN(R.string.core_settings_grammar_dialect_canadian_label),
    AUSTRALIAN(R.string.core_settings_grammar_dialect_australian_label),
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
 * Key-press sound. [CLICK] and [STANDARD] come from the device's own sound
 * pack, so they match the stock keyboard's palette; [POP], [THOCK] and [CHIME]
 * are synthesised in-app. [CUSTOM] plays a file from
 * [com.wasimaster.wmkeyboard.core.feedback.SoundStore], named by
 * [KeySoundSettings.customId].
 */
enum class KeySoundStyle { CLICK, STANDARD, POP, THOCK, CHIME, CUSTOM, PACK }

/**
 * The installed key sound in use.
 *
 * A nested class holding one field looks like overkill, and would be, except
 * that `KeyboardSettings` is at the JVM's 255-argument ceiling for the
 * `copy$default` Kotlin generates for it — see the note on [CameraSettings].
 * New settings go in a group; the DataStore key stays flat.
 */
data class KeySoundSettings(
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
 * It is [SYSTEM_TAP] that [KeyboardSettings.hapticStyle] declares, but almost
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

/** The character each [GlideApostropheKey] borrows, or null for [GlideApostropheKey.OFF]/SPACE. */
val GlideApostropheKey.sourceChar: Char?
    get() = when (this) {
        GlideApostropheKey.COMMA -> ','
        GlideApostropheKey.PERIOD -> '.'
        GlideApostropheKey.APOSTROPHE -> '\''
        GlideApostropheKey.OFF, GlideApostropheKey.SPACE -> null
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
     * With a physical keyboard attached, drop the on-screen keys and keep only
     * the toolbar strip, so the tools stay one tap away while typing on the
     * hardware keyboard. Off by default (the platform's usual behaviour stands).
     */
    val onlyWithHardwareKeyboard: Boolean = false,
    /**
     * Mirror the pinned tool order left-to-right when the active layout's
     * script runs right-to-left (Arabic, Hebrew …), so the bar reads with the
     * text. On by default; the toolbox grid is unaffected.
     */
    val reverseForRtl: Boolean = true,
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
     */
    val holdActions: Map<ToolbarTool, ToolbarTool> = emptyMap(),
)

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

/** True while the tools have a row of their own rather than sharing the strip. */
val ToolbarPlacement.isOwnRow: Boolean get() = this != ToolbarPlacement.STRIP

/**
 * The `tool=action` CSV behind [ToolbarBehavior.holdActions].
 *
 * A tool this build does not have is dropped on both sides rather than
 * corrupting the map — the same rule `IconOverrides` follows — so a map written
 * by a newer build costs that one entry and no more.
 */
object ToolHoldActions {

    fun decode(csv: String?): Map<ToolbarTool, ToolbarTool> =
        csv?.split(',')?.mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val tool = toolOrNull(entry.substring(0, separator)) ?: return@mapNotNull null
            val action = toolOrNull(entry.substring(separator + 1)) ?: return@mapNotNull null
            // A tool holding to itself is a tap done slowly; drop it rather than
            // firing the same action twice for one gesture.
            if (tool == action) null else tool to action
        }?.toMap().orEmpty()

    fun encode(map: Map<ToolbarTool, ToolbarTool>): String =
        map.entries.joinToString(",") { (tool, action) -> "${tool.name}=${action.name}" }

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
 * Clipboard/undo/redo shortcuts a letter key can perform on long press
 * (A/C/V/X/Z/Y). Grouped into their own class rather than sitting flat on
 * [KeyboardSettings] because that class's primary constructor is at the
 * JVM's 255-argument ceiling (see the class doc). Each field still persists
 * under its own DataStore key via the matching setter. All off by default —
 * each one replaces that key's accent popup, so turning any on is an
 * explicit trade a user opts into. Read as `settings.longPressLetterActions.selectAll`, etc.
 */
data class LongPressLetterActions(
    val selectAll: Boolean = false,
    val copy: Boolean = false,
    val paste: Boolean = false,
    val cut: Boolean = false,
    val undo: Boolean = false,
    val redo: Boolean = false,
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
) {
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

data class KeyboardSettings(
    /**
     * The layout being typed on: a [BuiltInLayouts] id, or a custom one. This is
     * the stored choice; [inputMode] below is read off it.
     */
    val activeLayoutId: String = BuiltInLayouts.DEFAULT_ID,
    /** Layouts the 🌐 key and the spacebar swipe cycle between, in order. */
    val enabledLayoutIds: List<String> = BuiltInLayouts.defaultEnabledIds,
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
    val dynamicColor: Boolean = true,
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
     * 245. As of 2026-09-05 the class has **236** fields (the nine flat
     * typing-test fields became one [TypingTestSettings]). A field past 245
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
    // Edge-to-edge IME windows (enforced on Android 15+) draw behind the
    // gesture bar; a larger default keeps the bottom row comfortably above it.
    val bottomPaddingDp: Int = if (Build.VERSION.SDK_INT >= 35) 32 else 8,
    val splitKeyboard: Boolean = false,
    val splitGapPercent: Int = 12,
    val floatingKeyboard: Boolean = false,
    val floatingWidthDp: Int = 320,
    /** Multiplier on key height while floating, set by the resize grip. */
    val floatingHeightScale: Float = 1f,
    val floatingXFraction: Float = 0.5f,
    val floatingYFraction: Float = 1.0f,
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
     */
    val autoDownloadLanguageData: Boolean = true,
    /**
     * Re-link romanized languages with the languages of their own script
     * every time a language is added (see [RomanizedPairing]).
     *
     * On by default, which is the long-standing behaviour and right for
     * almost everyone. Off matters for the user who deliberately unlinks a
     * pair: the auto-pairing runs on every add, so their removed link came
     * back the next time they touched the language list.
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
    val hapticFeedback: Boolean = true,
    val hapticStrengthMs: Int = 15,
    val hapticAmplitude: Int = 255,
    /**
     * See [HapticStyle]. Onboarding overwrites this with
     * `HapticPlayer.bestSupportedStyle(context)`, so on a phone that has been
     * through the wizard the style is [HapticStyle.SYSTEM_KEY] or
     * [HapticStyle.HEAVY_CLICK] and never this one. This value is what a reset
     * goes back to.
     */
    val hapticStyle: HapticStyle = HapticStyle.SYSTEM_TAP,
    val hapticOnLongPress: Boolean = true,
    val hapticOnLongPressRelease: Boolean = false,
    /** Per-event haptic gates + copy toast (see [FeedbackSettings]); nested to
     *  stay under the primary-constructor field ceiling. */
    val feedback: FeedbackSettings = FeedbackSettings(),
    val keySound: Boolean = false,
    val keySoundStyle: KeySoundStyle = KeySoundStyle.CLICK,
    /** Sound-effect volume, 0..1 of the system media volume. */
    val keySoundVolume: Float = 0.5f,
    /** Which installed sound [KeySoundStyle.CUSTOM] plays; see [KeySoundSettings]. */
    val keySoundCustom: KeySoundSettings = KeySoundSettings(),
    /** Key-preview bubble settings; see [KeyPopupSettings]. */
    val popup: KeyPopupSettings = KeyPopupSettings(),
    // ---- accessibility ----
    /** Daltonization / grayscale applied over the resolved theme palette. */
    val colorVisionFilter: ColorVisionFilter = ColorVisionFilter.NONE,
    /** Force key text to maximum contrast and separate the board from the keys. */
    val highContrastKeys: Boolean = false,
    /** Draw an outline on every key, so key edges don't rely on fill contrast. */
    val keyOutlines: Boolean = false,
    /** Render key labels bold. */
    val boldKeyLabels: Boolean = false,
    /**
     * Suppress non-essential animation across the keyboard and settings app,
     * for vestibular sensitivity. Feedback that carries meaning (the key
     * preview bubble, press colour) is untouched — only motion is removed.
     */
    val reduceMotion: Boolean = false,
    /**
     * See [ScreenReaderMode]. [ScreenReaderMode.EXPLORE] by default: it is what
     * every other IME does under touch exploration, so a TalkBack user meets
     * the gesture they already know rather than one this keyboard invented.
     */
    val screenReaderMode: ScreenReaderMode = ScreenReaderMode.EXPLORE,
    /**
     * Ignore a repeat press of the same key within this many milliseconds
     * (0 = off). The tremor/spasticity counterpart to a long-press delay:
     * it drops the unintended second contact of a bouncing tap.
     */
    val keyDebounceMs: Int = 0,
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
    val autocorrect: Boolean = true,
    /**
     * How sure autocorrect must be before it replaces a word: the factor by
     * which the best candidate has to outscore the runner-up. Low corrects
     * eagerly, high only on near-certainty. Mirrors
     * `SuggestionEngine.DEFAULT_AUTOCORRECT_CONFIDENCE`, spelled out here
     * because prediction already depends on this package.
     */
    val autocorrectConfidence: Float = 4f,
    /**
     * Scale the confidence gate by the user's recent revert rate: a keyboard
     * whose corrections keep getting undone demands more certainty before
     * forcing anything. The slider above stays the anchor either way.
     */
    val autocorrectAdaptive: Boolean = true,
    /** Backspace right after an autocorrect puts the typed word back. */
    val revertAutocorrectOnBackspace: Boolean = true,
    /** Never autocorrect a word typed all in capitals (acronyms, shouting). */
    val autocorrectSkipAllCaps: Boolean = true,
    /** Fix missing apostrophes on commit: arent → aren't, im → I'm. */
    val autoApostrophe: Boolean = true,
    val autoCapitalize: Boolean = true,
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
    val autoSpaceAfterPunctuation: Boolean = false,
    val suggestions: Boolean = true,
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
    val showSuggestionsInAllFields: Boolean = true,
    // `suggestionsFirst` and `suggestionPrimaryCenter` moved into
    // [SuggestionStripSettings] to keep this constructor under the JVM slot
    // ceiling; their DataStore keys are unchanged.
    /** Suggest names from the phone's contacts (needs the Contacts permission). */
    val contactSuggestions: Boolean = false,
    /**
     * Complete a contact's email address as you type the start of it — "john"
     * offers john.doe@gmail.com. Needs the Contacts permission.
     */
    val contactEmailSuggestions: Boolean = false,
    /**
     * Show those email completions inside email fields too, even when the app
     * has asked for no suggestion strip (which email fields normally do). Only
     * matters while [contactEmailSuggestions] is on.
     */
    val contactEmailSuggestionsInEmailFields: Boolean = true,
    /** Suggest the names of installed apps ("sign" → Signal). No permission needed. */
    val appNameSuggestions: Boolean = false,
    /**
     * Words the user never wants suggested or autocorrected to. Matched
     * case-insensitively; the word can still be typed and committed, it is
     * only kept out of the suggestion strip. Empty by default.
     */
    val suggestionBlacklist: Set<String> = emptySet(),
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
    /** Height of the top toolbar/suggestion strip, in dp. */
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
    /** Turn the torch off automatically when the keyboard is dismissed. */
    val flashlightAutoOff: Boolean = true,
    val compassShowDegrees: Boolean = true,
    /** Mark the direction of the Kaaba on the compass (needs the saved location). */
    val compassShowQibla: Boolean = false,
    val levelShowAngles: Boolean = true,
    /** Redo sends Ctrl+Y instead of Ctrl+Shift+Z. */
    val redoUsesCtrlY: Boolean = false,
    /**
     * Mirrors the moon drawing for southern-hemisphere viewers. Starts from the
     * device's region (see [isSouthernHemisphere]) rather than false, since
     * which way a crescent faces is a fact about where you are and not a taste;
     * left as it was, half the world is shown the wrong moon until it notices.
     */
    val moonSouthernHemisphere: Boolean = false,
    val weatherFahrenheit: Boolean = false,
    val weatherLatitude: Float? = null,
    val weatherLongitude: Float? = null,
    val weatherPlaceName: String = "",
    /**
     * The two calendars the tool shows alongside the Gregorian one, in the
     * order they are drawn. The first is also what the day cells get their
     * small second number from. Either may be [AltCalendar.NONE].
     */
    val calendarAltOne: AltCalendar = AltCalendar.NONE,
    val calendarAltTwo: AltCalendar = AltCalendar.NONE,
    /**
     * Days the month grid tints as the weekend. Starts from the device's region
     * (see [Weekend.forRegion]) rather than a fixed pair, since which days are
     * the weekend is exactly the sort of thing that differs by where you are.
     */
    val calendarWeekend: Weekend = Weekend.SAT_SUN,
    /** Day offset applied to the tabular Hijri date (moon-sighting drift). */
    val hijriAdjustDays: Int = 0,
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
    /** Copy scanned document pages into Pictures/WM Keyboard. */
    val docScanSaveToGallery: Boolean = false,
    /** Copy generated QR codes into Pictures/WM Keyboard. */
    val qrSaveToGallery: Boolean = false,
    /** How sticker-tool picks are sent. WhatsApp shows real stickers for these. */
    val stickerSendMode: MediaSendMode = MediaSendMode.STICKER,
    /** How GIF picks are sent. Sticker mode only applies to WebP-backed GIFs. */
    val gifSendMode: MediaSendMode = MediaSendMode.IMAGE,
    /** How generated QR codes are sent. */
    val qrSendMode: MediaSendMode = MediaSendMode.IMAGE,
    /** Dictionary tool looks up the word at the cursor when it opens. */
    val dictionaryAutoLookup: Boolean = true,
    /** Text-editing tool and selection-editing settings (see [TextEditingSettings]). */
    val textEditing: TextEditingSettings = TextEditingSettings(),
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
    /** Text scanner results start with every word selected (deselect to trim). */
    val ocrAutoSelectWords: Boolean = true,
    /** Vibrate when the QR scanner spots a code. */
    val qrScanHaptics: Boolean = true,
    /** Insert a scanned code into the field the moment it is spotted. */
    val qrScanAutoInsert: Boolean = false,
    /** Fetch the page title/description for a scanned link, like clipboard link previews. */
    val qrScanLinkPreviews: Boolean = false,
    /** Decimal places on currency conversion results. */
    val currencyDecimals: Int = 2,
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
    /** English dialect the offline grammar tool checks against. */
    val grammarDialect: GrammarDialect = GrammarDialect.AMERICAN,
    /**
     * Squiggle spelling errors but offer no fix popup when Harper acts as the
     * system spell checker. Only has an effect on Android 12+, where the
     * framework honours the "mark but don't show suggestions UI" flag.
     */
    val spellCheckerNoSuggestions: Boolean = false,
    /**
     * User-supplied API keys, overriding any key baked into the build.
     * Blank means "use the built-in key" (which may itself be blank).
     */
    val translateApiKey: String = "",
    val klipyApiKey: String = "",
    val giphyApiKey: String = "",
    val braveApiKey: String = "",
    val gifContentFilter: GifContentFilter = GifContentFilter.MEDIUM,
    /** Tabs per provider vs one evenly-mixed grid, when several have keys. */
    val gifSourceMode: GifSourceMode = GifSourceMode.TABS,
    /** GIF/sticker results per search or trending fetch (local packs exempt). */
    val gifResultLimit: Int = 24,
    /** SafeSearch for the web and image search tools. */
    val searchSafe: Boolean = true,
    /** Results per web/image search (the API caps a page at 10). */
    val searchResultCount: Int = 8,
    /** Wikipedia subdomain the encyclopedia tool reads (en, bn, de …). */
    val wikiLanguage: String = "en",
    /** Insert Wikipedia links as `[Title](url)` instead of the bare URL. */
    val wikiLinksMarkdown: Boolean = false,
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
    val barOrder: List<BarRow> = listOf(BarRow.EMOJI, BarRow.TOPBAR, BarRow.SYMBOL),
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
    /** Side length of the QR image the generator inserts. */
    val qrSizePx: Int = 1024,
    val qrEcc: QrEccLevel = QrEccLevel.M,
    /** Everything the AI tool owns — see [AiSettings]. */
    val ai: AiSettings = AiSettings(),
    /** The one-time-code chip fed by the notification listener — see [OtpSettings]. */
    val otp: OtpSettings = OtpSettings(),
    /** The backup that writes itself to a folder — see [AutoBackupSettings]. */
    val autoBackup: AutoBackupSettings = AutoBackupSettings(),
)

/**
 * Every setting at the value it shipped with, as one object to read a single
 * default out of: `SettingsDefaults.hapticFeedback`, `SettingsDefaults.otp.enabled`.
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
     * Only raise the chip when the focused field asks for digits — the shape
     * every code box has. Off shows the chip in any ordinary field, for the
     * apps that put their code box behind a plain text input.
     */
    val numberFieldsOnly: Boolean = true,
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
 * Three destinations, none of them a server of ours. That is the whole shape of
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
 * and not as an account. See [BackupDestination] for the three places it can go.
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
        )
    }
}

/** [AutoBackupSettings.sections] as the sections themselves. */
val AutoBackupSettings.sectionSet: Set<ConfigBackup.Section>
    get() = ConfigBackup.Section.entries.filterTo(LinkedHashSet()) { it.id in sections }

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
val LEARNED_DATA_FILES = listOf(
    "learning/user_lexicon.json",
    "learning/pending_learn.json",
    "learning/emoji_usage.json",
    "learning/correction_stats.json",
    "learning/cjk_history.json",
    "learning/language_mix.json",
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
) {
    companion object {
        const val MAX_RECENTS = 10
        val RECENTS_RANGE = 4..20
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
)

data class RateSourceSettings(
    val fiatProviders: List<String> = listOf("ER_API", "FRANKFURTER"),
    /** Read coin amounts ("1 btc") and show coins in the converter. */
    val cryptoEnabled: Boolean = true,
    val cryptoProviders: List<String> = listOf("COINBASE", "CURRENCY_API"),
    /** Coin prices move by the minute, unlike the daily fiat table. */
    val cryptoCacheMinutes: Int = 5,
    /** The coins that are on; empty means the catalogue's own default set. */
    val cryptoTickers: Set<String> = emptySet(),
    /** Decimal places on coin amounts, or 0 to keep significant digits instead. */
    val cryptoDecimals: Int = 0,
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
    /** Which region's vocabulary Traditional output should prefer. */
    val hanRegion: HanVariant.HanRegion = HanVariant.HanRegion.GENERIC,
)

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
    /** Dictation backend: "system" = OS SpeechRecognizer, "whisper" = offline LiteRT. */
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
)

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
     * The text-editing panel's own grid, or null for the shipped arrangement
     * ([DefaultTextEditLayout]).
     *
     * One layout, not one per language: the panel holds cursor moves and
     * clipboard actions, and none of them is a letter. Stored whole, and repaired
     * on read — see [TextEditLayoutCodec].
     */
    val layout: TextEditLayout? = null,
    /**
     * Typing a bracket, brace or quote with text selected wraps the selection
     * in the pair (foo → (foo)) instead of replacing it.
     */
    val wrapSelectionWithPair: Boolean = true,
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
     * How far a backspace swipe drags before the first word goes.
     *
     * Later words come cheaper on a fixed curve derived from this one, so a
     * single number tunes the whole gesture: raise it if words disappear by
     * accident, lower it if clearing a sentence is a marathon. 72 dp is the
     * long-standing constant, which is a long pull on a small phone and a
     * twitch on a tablet.
     */
    val backspaceWordStepDp: Int = 72,
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
)

/** What the symbol row's height slider offers, matching the number row's. */
val SymbolRowHeightRange = 28..64

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
     * How long a mode picked by hand from the Modes tool lasts.
     *
     * The keyboard cleared it on the next app switch with no way to say
     * otherwise, which is right for a mode that was picked in passing and
     * wrong for one picked deliberately.
     */
    val manualModeDuration: ManualModeDuration = ManualModeDuration.UNTIL_APP_CHANGES,
)

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

    /** Only in a field that asks for digits, where the code is all you type. */
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
     */
    val sensitiveHandling: SensitiveClipHandling = SensitiveClipHandling.SHORT_LIVED,
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
    /**
     * Show an abc / space / backspace control row at the bottom of the clipboard
     * panel, like the emoji panel's, so a quick paste needs no detour to the keys.
     */
    val bottomRow: Boolean = false,
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
     * otherwise sit there — readable by every app — until it expired. On by
     * default; turning it off keeps the clip like any other paste.
     */
    val clearAfterPasswordPaste: Boolean = true,
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
)

/** Bounds for [EmojiSettings.barCount]; the settings slider shares them. */
val EmojiBarCountRange = 3..16

/** Bounds for [EmojiSettings.gridCellSize]; the settings slider shares them. */
val EmojiGridCellSizeRange = 36..64

/** Bounds for [EmojiSettings.recentsLimit]; the settings slider shares them. */
val EmojiRecentsRange = 8..96

/** Bounds for [EmojiSettings.gridEmojiSize]; the settings slider shares them. */
val EmojiGridEmojiSizeRange = 20..36

/** Glide-typing behaviour and swipe-trail appearance. See [KeyboardSettings.gesture]. */
data class GestureSettings(
    /**
     * Swiping over the spacebar mid-glide commits the current word and starts a
     * new one, so several words can be glided in one unbroken stroke. On by
     * default; off makes a swipe that crosses the spacebar decode as one word.
     */
    val spaceGlideMultiWord: Boolean = true,
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
     */
    val ambiguityPicker: Boolean = true,
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
     * A short swipe from the apostrophe key to `s`, drawn straight after a glided
     * word, appends `'s` to it: "developer" becomes "developer's" without a trip
     * to the symbols layer.
     *
     * On by default and does nothing until [apostropheKey] names a key. Never the
     * spacebar, whichever key is chosen for the apostrophe itself: a stroke that
     * starts on the spacebar is how a glide is separated, so it cannot also be
     * how one is extended.
     */
    val apostropheS: Boolean = true,
    /**
     * A glided word is followed by a space, so the next word — glided or tapped
     * — starts clean instead of running into it. On by default. The space is
     * the keyboard's, not the user's: punctuation typed straight after takes it
     * back ("hello." not "hello ."), and a space press right after is spent
     * confirming it rather than doubling it.
     */
    val autoSpaceAfterGlide: Boolean = true,
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
     * Swiping straight down on the spacebar dismisses the keyboard, the way a
     * downward flick on the toolbar can. Off by default so a stray vertical
     * drag never closes the keyboard mid-type.
     */
    val spaceSwipeDownHide: Boolean = false,
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
     * Size multiplier for the small corner hint character on each key (the
     * first long-press alternate, shown when [KeyboardSettings.longPressHints]
     * is on). 1.0 keeps the default 10sp base.
     */
    val hintFontScale: Float = 1.0f,
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
     * long-standing behaviour); off keeps the digit row on the letters layer but
     * drops it from ?123, where the symbols already carry their own top row.
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
     * Symmetric horizontal padding added to both edges of the keyboard, as a
     * fraction of its width per side (0 = none, the default; 0.15 = 15% shaved
     * off each side). Narrows the keys toward the centre for thumb reach without
     * docking to one side the way one-handed mode does. See [SidePadScaleRange].
     */
    val sidePadScale: Float = 0f,
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
) {
    /** The characters that spring the symbols layer back, with the default applied. */
    fun symbolsReturnCharSet(): String =
        symbolsReturnChars.ifEmpty { DefaultSymbolsReturnChars }

    /** [langId]'s numeral system, [NumeralSystem.AUTO] when it has no entry. */
    fun numeralSystemFor(langId: String): NumeralSystem =
        numeralSystemByLang[langId] ?: NumeralSystem.AUTO
}

/** Bounds for [LayoutBehaviorSettings.sidePadScale]; the settings slider shares them. */
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
    /** Keep the suggestion strip as the default top bar even with nothing typed. */
    val suggestionsFirst: Boolean = false,
    /** Show the primary candidate in the middle slot (Gboard style) instead of the left. */
    val suggestionPrimaryCenter: Boolean = true,
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
     * [KeyboardSettings.inlineAutofill] only to stay under that class's JVM
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
) {
    /** Whether the fixed-spelling map applies to [langId]. */
    fun spellingMapEnabledFor(langId: String): Boolean = langId !in spellingMapOffLangs
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
        private fun sidePadScaleKey(v: ScreenVariant) =
            floatPreferencesKey(variantKey("side_pad_scale", v))
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
        private val REVERT_AUTOCORRECT_ON_BACKSPACE =
            booleanPreferencesKey("revert_autocorrect_on_backspace")
        private val AUTOCORRECT_SKIP_ALL_CAPS =
            booleanPreferencesKey("autocorrect_skip_all_caps")
        private val AUTO_CAPITALIZE = booleanPreferencesKey("auto_capitalize")
        private val DOUBLE_SPACE_PERIOD = booleanPreferencesKey("double_space_period")
        private val DOUBLE_SPACE_TAB = booleanPreferencesKey("double_space_tab")
        private val AUTO_SPACE_AFTER_PUNCTUATION =
            booleanPreferencesKey("auto_space_after_punctuation")
        private val WRAP_SELECTION_WITH_PAIR = booleanPreferencesKey("wrap_selection_with_pair")
        private val TEXT_EDIT_LAYOUT = stringPreferencesKey("text_edit_layout")
        private val RECAPITALIZE_SELECTION_WITH_SHIFT =
            booleanPreferencesKey("recapitalize_selection_with_shift")
        private val SUGGESTIONS = booleanPreferencesKey("suggestions")
        private val SHOW_SUGGESTIONS_ALL_FIELDS =
            booleanPreferencesKey("show_suggestions_all_fields")
        private val SUGGESTIONS_FIRST = booleanPreferencesKey("suggestions_first")
        private val SUGGESTION_PRIMARY_CENTER = booleanPreferencesKey("suggestion_primary_center")
        private val BLOCK_OFFENSIVE_WORDS = booleanPreferencesKey("block_offensive_words")
        private val CONTEXT_RERANK = booleanPreferencesKey("context_rerank")
        private val LANGUAGE_DETECTION = booleanPreferencesKey("language_detection")
        private val LANGUAGE_DETECTION_STRENGTH =
            stringPreferencesKey("language_detection_strength")
        private val NUMBER_ROW_CORRECTIONS = booleanPreferencesKey("number_row_corrections")
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
        private val SPELLING_MAP_OFF_LANGS = stringSetPreferencesKey("spelling_map_off_langs")
        private val INLINE_EMOJI_SEARCH = booleanPreferencesKey("inline_emoji_search")
        private val INLINE_AUTOFILL = booleanPreferencesKey("inline_autofill")
        private val GESTURE_TYPING = booleanPreferencesKey("gesture_typing")
        private val LETTER_SWIPE_ACTION = stringPreferencesKey("letter_swipe_action")
        private val GESTURE_SPACE_MULTI_WORD = booleanPreferencesKey("gesture_space_multi_word")
        private val GESTURE_AMBIGUITY_PICKER = booleanPreferencesKey("gesture_ambiguity_picker")
        private val GESTURE_APOSTROPHE_KEY = stringPreferencesKey("gesture_apostrophe_key")
        private val GESTURE_APOSTROPHE_S = booleanPreferencesKey("gesture_apostrophe_s")
        private val GESTURE_AUTO_SPACE = booleanPreferencesKey("gesture_auto_space")
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
        // Legacy boolean, read only to migrate into SPACE_LONG_SWIPE.
        private val SPACEBAR_CURSOR = booleanPreferencesKey("spacebar_cursor")
        private val SPACE_SHORT_SWIPE = stringPreferencesKey("space_short_swipe")
        private val SPACE_LONG_SWIPE = stringPreferencesKey("space_long_swipe")
        private val SPACEBAR_LANGUAGE_ARROWS = booleanPreferencesKey("spacebar_language_arrows")
        private val SPACEBAR_LABEL = stringPreferencesKey("spacebar_label")
        private val SYMBOLS_LONGPRESS_NUMPAD = booleanPreferencesKey("symbols_longpress_numpad")
        private val SPACE_SWIPE_DOWN_HIDE = booleanPreferencesKey("space_swipe_down_hide")
        private val SPACE_CURSOR_2D = booleanPreferencesKey("space_cursor_2d")
        private val HINT_FONT_SCALE = floatPreferencesKey("hint_font_scale")
        private val FANCY_STYLE = stringPreferencesKey("fancy_style")
        private val FANCY_TOOL_STYLE = stringPreferencesKey("fancy_tool_style")
        private val FANCY_TOOL_KEEPS_LANGUAGE =
            booleanPreferencesKey("fancy_tool_keeps_language")
        private val FANCY_TOOL_AUTO_OFF = booleanPreferencesKey("fancy_tool_auto_off")
        private val NUMBER_ROW_SHIFT_SYMBOLS = booleanPreferencesKey("number_row_shift_symbols")
        private val NUMBER_ROW_IN_SYMBOLS = booleanPreferencesKey("number_row_in_symbols")
        private val BOTTOM_ROW_HEIGHT = intPreferencesKey("bottom_row_height")
        private val SIDE_PAD_SCALE = floatPreferencesKey("side_pad_scale")
        private val SPLIT_ONLY_LARGE = booleanPreferencesKey("split_only_large_screens")
        private val SHIFT_CAPS_LOCK_MS = intPreferencesKey("shift_caps_lock_ms")
        private val SHOW_ALL_POPUP_KEYS = booleanPreferencesKey("show_all_popup_keys")
        private val CURRENCY_KEYS = stringPreferencesKey("currency_keys")
        private val SPACE_HOLD_KEYS = stringPreferencesKey("space_hold_keys")
        private val SYMBOLS_RETURN_TO_LETTERS =
            booleanPreferencesKey("symbols_return_to_letters")
        private val SYMBOLS_RETURN_CHARS = stringPreferencesKey("symbols_return_chars")
        private val AUTO_SPACE_AFTER_SUGGESTION = booleanPreferencesKey("auto_space_after_suggestion")
        private val EXPAND_USER_DICT_SHORTCUTS = booleanPreferencesKey("expand_user_dict_shortcuts")
        private val USE_SYSTEM_DICTIONARY = booleanPreferencesKey("use_system_dictionary")
        private val SNIPPET_MULTI_EXPAND = stringPreferencesKey("snippet_multi_expand")
        private val SYSTEM_SMART_REPLIES = booleanPreferencesKey("system_smart_replies")
        private val SMART_HIT_DETECTION = booleanPreferencesKey("smart_hit_detection")
        private val SPACEBAR_DISPLAY = stringPreferencesKey("spacebar_display")
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
        private val DS_MANUAL = booleanPreferencesKey("data_saver_manual")
        private val DS_TRIGGER = stringPreferencesKey("data_saver_trigger")
        private val DS_LINK_PREVIEWS = stringPreferencesKey("data_saver_link_previews")
        private val DS_DICTIONARY_LOOKUP = stringPreferencesKey("data_saver_dictionary_lookup")
        private val DS_PHOTO_BACKGROUNDS = stringPreferencesKey("data_saver_photo_backgrounds")
        private val DS_WEATHER_CHIP = stringPreferencesKey("data_saver_weather_chip")
        private val DS_CURRENCY_RATES = stringPreferencesKey("data_saver_currency_rates")
        private val DS_ADDON_REFRESH = stringPreferencesKey("data_saver_addon_refresh")
        private val DS_MEDIA_SEARCH = stringPreferencesKey("data_saver_media_search")
        private val DS_WEB_SEARCH = stringPreferencesKey("data_saver_web_search")
        private val DS_ANIMATED_EMOJI = stringPreferencesKey("data_saver_animated_emoji")
        private val DS_DOWNLOADS = stringPreferencesKey("data_saver_downloads")
        private val DS_CLOUD_AI = stringPreferencesKey("data_saver_cloud_ai")
        private val BACKSPACE_SWIPE_DELETE = booleanPreferencesKey("backspace_swipe_delete")
        private val HARDWARE_KEYBOARD_INPUT = booleanPreferencesKey("hardware_keyboard_input")
        private val HW_SHORTCUTS_ENABLED = booleanPreferencesKey("hw_shortcuts_enabled")
        private val HW_PANEL_NAVIGATION = booleanPreferencesKey("hw_panel_navigation")
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
        private val DEFAULT_WORDLIST_SIZE = stringPreferencesKey("default_wordlist_size")
        private val SYMBOL_ROW_HEIGHT = intPreferencesKey("symbol_row_height")
        private val WEATHER_REFRESH_MINUTES = intPreferencesKey("weather_refresh_minutes")
        private val WIKI_LINK_LIMIT = intPreferencesKey("wiki_link_limit")
        private val QR_MAX_CHARS = intPreferencesKey("qr_max_chars")
        private val PASSWORD_SYMBOLS = stringPreferencesKey("password_symbols")
        private val MANUAL_MODE_DURATION = stringPreferencesKey("manual_mode_duration")
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
        private val CLIPBOARD_BOTTOM_ROW = booleanPreferencesKey("clipboard_bottom_row")
        private val CLIPBOARD_PINNED_LAST = booleanPreferencesKey("clipboard_pinned_last")
        private val CLIPBOARD_SEARCH = booleanPreferencesKey("clipboard_search")
        private val CLIPBOARD_USER_SCREENSHOTS = booleanPreferencesKey("clipboard_user_screenshots")
        private val CLIPBOARD_CLEAR_AFTER_PASSWORD_PASTE =
            booleanPreferencesKey("clipboard_clear_after_password_paste")
        private val CLIPBOARD_DETECT_ENTITIES = booleanPreferencesKey("clipboard_detect_entities")
        private val CLIPBOARD_PHONE_FORMATS = stringSetPreferencesKey("clipboard_phone_formats")
        private val CLIPBOARD_FULL_BLEED = booleanPreferencesKey("clipboard_full_bleed")
        private val OTP_CHIP_ENABLED = booleanPreferencesKey("otp_chip_enabled")
        private val OTP_NUMBER_FIELDS_ONLY = booleanPreferencesKey("otp_number_fields_only")
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
        private val LONG_PRESS_DELAY = intPreferencesKey("long_press_delay")
        // The pre-split single interval. Still read, as the fallback for both
        // keys below, so a cadence tuned before the split survives the upgrade.
        private val KEY_REPEAT_INTERVAL = intPreferencesKey("key_repeat_interval")
        private val KEY_REPEAT_DELETE = intPreferencesKey("key_repeat_delete")
        private val KEY_REPEAT_SPACE = intPreferencesKey("key_repeat_space")
        private val KEY_REPEAT_START_DELAY = intPreferencesKey("key_repeat_start_delay")
        private val LONG_PRESS_HINTS = booleanPreferencesKey("long_press_hints")
        private val LONG_PRESS_A_SELECT_ALL = booleanPreferencesKey("long_press_a_select_all")
        private val LONG_PRESS_C_COPY = booleanPreferencesKey("long_press_c_copy")
        private val LONG_PRESS_V_PASTE = booleanPreferencesKey("long_press_v_paste")
        private val LONG_PRESS_X_CUT = booleanPreferencesKey("long_press_x_cut")
        private val LONG_PRESS_Z_UNDO = booleanPreferencesKey("long_press_z_undo")
        private val LONG_PRESS_Y_REDO = booleanPreferencesKey("long_press_y_redo")
        private val LONG_PRESS_LETTERS = stringPreferencesKey("long_press_letters")
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
        private val TOOLBAR_PLACEMENT = stringPreferencesKey("toolbar_placement")
        private val TOOLBAR_HOLD_ACTIONS = stringPreferencesKey("toolbar_hold_actions")
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
        private val EMOJI_USAGE_VERSION = intPreferencesKey("emoji_usage_version")
        private val EMOJI_RECENTS_LIMIT = intPreferencesKey("emoji_recents_limit")
        private val MEDIA_GRID_COLUMNS = intPreferencesKey("media_grid_columns")
        private val EMOJI_ANIMATED = booleanPreferencesKey("emoji_animated")
        private val EMOJI_SEND_AS_STICKER = booleanPreferencesKey("emoji_send_as_sticker")
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
        private val WEATHER_FAHRENHEIT = booleanPreferencesKey("weather_fahrenheit")
        private val WEATHER_LAT = floatPreferencesKey("weather_lat")
        private val WEATHER_LON = floatPreferencesKey("weather_lon")
        private val WEATHER_PLACE = stringPreferencesKey("weather_place")
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
        private val VOICE_UI_RETURN_MODE = stringPreferencesKey("voice_ui_return_mode")
        private val VOICE_BAR_INLINE = booleanPreferencesKey("voice_bar_inline")
        private val VOICE_CONTINUOUS = booleanPreferencesKey("voice_continuous")
        private val VOICE_SPOKEN_PUNCTUATION = booleanPreferencesKey("voice_spoken_punctuation")
        private val VOICE_ENGINE = stringPreferencesKey("voice_engine")
        private val WHISPER_MODEL_ID = stringPreferencesKey("whisper_model_id")
        private val WHISPER_MODEL_BY_LANG = stringPreferencesKey("whisper_model_by_lang")
        private val WHISPER_TRANSLATE = booleanPreferencesKey("whisper_translate")
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
        private val DOC_SCAN_SAVE_TO_GALLERY = booleanPreferencesKey("doc_scan_save_to_gallery")
        private val QR_SAVE_TO_GALLERY = booleanPreferencesKey("qr_save_to_gallery")
        private val STICKER_SEND_MODE = stringPreferencesKey("sticker_send_mode")
        private val GIF_SEND_MODE = stringPreferencesKey("gif_send_mode")
        private val QR_SEND_MODE = stringPreferencesKey("qr_send_mode")
        private val DICTIONARY_AUTO_LOOKUP = booleanPreferencesKey("dictionary_auto_lookup")
        private val TEXT_EDIT_REPEAT_MS = intPreferencesKey("text_edit_repeat_ms")
        private val CURSOR_TOOLS_REPEAT_ON_HOLD =
            booleanPreferencesKey("cursor_tools_repeat_on_hold")
        private val TOOLBOX_REPEAT_TOOLS = stringPreferencesKey("toolbox_repeat_tools")
        private val SELECTION_MODE_HOLD = booleanPreferencesKey("selection_mode_hold")
        private val SELECTION_MODE_MULTI_TAP = booleanPreferencesKey("selection_mode_multi_tap")
        private val DOUBLE_SPACE_WINDOW_MS = intPreferencesKey("double_space_window_ms")
        private val SPACE_CURSOR_STEP_DP = intPreferencesKey("space_cursor_step_dp")
        private val BACKSPACE_WORD_STEP_DP = intPreferencesKey("backspace_word_step_dp")
        private val PUNCTUATION_CHIPS = stringPreferencesKey("punctuation_chips")
        private val SUGGESTION_SLOT_COUNT = intPreferencesKey("suggestion_slot_count")
        private val SUGGESTION_SCROLLABLE = booleanPreferencesKey("suggestion_scrollable")
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
        private val QR_SCAN_HAPTICS = booleanPreferencesKey("qr_scan_haptics")
        private val QR_SCAN_AUTO_INSERT = booleanPreferencesKey("qr_scan_auto_insert")
        private val QR_SCAN_LINK_PREVIEWS = booleanPreferencesKey("qr_scan_link_previews")
        private val CURRENCY_DECIMALS = intPreferencesKey("currency_decimals")
        private val CURRENCY_CACHE_HOURS = intPreferencesKey("currency_cache_hours")
        private val FIAT_PROVIDERS = stringPreferencesKey("fiat_rate_providers")
        private val CRYPTO_ENABLED = booleanPreferencesKey("crypto_enabled")
        private val CRYPTO_PROVIDERS = stringPreferencesKey("crypto_rate_providers")
        private val CRYPTO_CACHE_MINUTES = intPreferencesKey("crypto_cache_minutes")
        private val CRYPTO_TICKERS = stringSetPreferencesKey("crypto_tickers")

        // The settings app's fingerprint lock; see [AppLockSettings]. Flat
        // keys like everything else here, even though the in-memory shape is
        // its own object rather than a KeyboardSettings field.
        private val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        private val APP_LOCK_TARGETS = stringSetPreferencesKey("app_lock_targets")
        private val APP_LOCK_RELOCK = stringPreferencesKey("app_lock_relock")
        private val APP_LOCK_ALLOW_CREDENTIAL = booleanPreferencesKey("app_lock_allow_credential")
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
        private val SUGGESTION_TEXT_SCALE = floatPreferencesKey("suggestion_text_scale")
        private val LEARNED_WORD_MIN_COUNT = intPreferencesKey("learned_word_min_count")
        private val NEW_WORD_SIGHTINGS = intPreferencesKey("new_word_sightings")
        private val ASK_BEFORE_LEARNING = booleanPreferencesKey("ask_before_learning")
        private val OFFER_NEAR_MISS_CORRECTIONS =
            booleanPreferencesKey("offer_near_miss_corrections")
        private val EMOJI_ROW_ABOVE_TOOLBAR = booleanPreferencesKey("emoji_row_above_toolbar")
        private val TRANSLATE_TARGET_LANG = stringPreferencesKey("translate_target_lang")
        private val GRAMMAR_DIALECT = stringPreferencesKey("grammar_dialect")
        private val SPELL_CHECKER_NO_SUGGESTIONS =
            booleanPreferencesKey("spell_checker_no_suggestions")
        private val TRANSLATE_API_KEY = stringPreferencesKey("translate_api_key")
        private val KLIPY_API_KEY = stringPreferencesKey("klipy_api_key")
        private val BRAVE_API_KEY = stringPreferencesKey("brave_api_key")
        private val GIPHY_API_KEY = stringPreferencesKey("giphy_api_key")
        private val GIF_SOURCE_MODE = stringPreferencesKey("gif_source_mode")
        private val GIF_CONTENT_FILTER = stringPreferencesKey("gif_content_filter")
        private val GIF_RESULT_LIMIT = intPreferencesKey("gif_result_limit")
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
        private val TOOL_KEYWORDS = stringPreferencesKey("tool_keywords")
        private val TOOL_KEYWORD_CASE = stringPreferencesKey("tool_keyword_case")
        private val CALC_DEGREES = booleanPreferencesKey("calc_degrees")
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
        // frame it was trying to appear. [mapPreferences] touches no Context
        // and no disk, so it is free to move; its one piece of outside state
        // is the shipped-layout catalogue, which is immutable except for the
        // once-per-process [AssetLayouts.load] — and the keyboard re-resolves
        // the layout for itself on every field focus rather than relying on
        // this object's copy, so a mapping that ran before the assets finished
        // parsing is not what decides which grid gets drawn.
        .map { mapPreferences(it) }
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
     */
    private suspend fun editPrefs(transform: suspend (MutablePreferences) -> Unit) {
        if (unlocked.value) context.dataStore.edit { transform(it) }
        else locked.edit { transform(it) }
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
            autoTheme = AutoThemeSettings(
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
            ),
            photoBackground = PhotoBackgroundSettings(
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
            ),
            keyHeightDp = p[KEY_HEIGHT] ?: defaults.keyHeightDp,
            numberRowHeightDp = p[NUMBER_ROW_HEIGHT] ?: p[KEY_HEIGHT] ?: defaults.numberRowHeightDp,
            bottomPaddingDp = p[BOTTOM_PADDING] ?: defaults.bottomPaddingDp,
            splitKeyboard = p[SPLIT_KEYBOARD] ?: defaults.splitKeyboard,
            splitGapPercent = p[SPLIT_GAP_PERCENT] ?: defaults.splitGapPercent,
            floatingKeyboard = p[FLOATING_KEYBOARD] ?: defaults.floatingKeyboard,
            floatingWidthDp = p[FLOATING_WIDTH] ?: defaults.floatingWidthDp,
            floatingHeightScale = p[FLOATING_HEIGHT_SCALE] ?: defaults.floatingHeightScale,
            floatingXFraction = p[FLOATING_X] ?: defaults.floatingXFraction,
            floatingYFraction = p[FLOATING_Y] ?: defaults.floatingYFraction,
            keyboardWidthPercent = p[KEYBOARD_WIDTH_PERCENT] ?: defaults.keyboardWidthPercent,
            keyboardAlignment = p[KEYBOARD_ALIGNMENT]
                ?.let { runCatching { KeyboardAlignment.valueOf(it) }.getOrNull() }
                ?: defaults.keyboardAlignment,
            keyGapScale = p[KEY_GAP_SCALE] ?: defaults.keyGapScale,
            keyCornerRadiusDp = p[KEY_CORNER_RADIUS] ?: defaults.keyCornerRadiusDp,
            fontScale = p[FONT_SCALE] ?: defaults.fontScale,
            sizingOverrides = ScreenVariant.entries
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
                        sidePadScale = p[sidePadScaleKey(v)],
                        bottomRowHeightDp = p[bottomRowHeightKey(v)],
                        numberRow = p[variantNumberRowKey(v)],
                    )
                }
                .filterValues { !it.isEmpty },
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
            hapticFeedback = p[HAPTIC] ?: defaults.hapticFeedback,
            hapticStrengthMs = p[HAPTIC_STRENGTH] ?: defaults.hapticStrengthMs,
            hapticAmplitude = p[HAPTIC_AMPLITUDE] ?: defaults.hapticAmplitude,
            hapticStyle = p[HAPTIC_STYLE]?.let { runCatching { HapticStyle.valueOf(it) }.getOrNull() }
                ?: defaults.hapticStyle,
            hapticOnLongPress = p[HAPTIC_ON_LONG_PRESS] ?: defaults.hapticOnLongPress,
            hapticOnLongPressRelease = p[HAPTIC_ON_LONG_PRESS_RELEASE]
                ?: defaults.hapticOnLongPressRelease,
            feedback = FeedbackSettings(
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
            ),
            keySound = p[KEY_SOUND] ?: defaults.keySound,
            keySoundStyle = p[KEY_SOUND_STYLE]
                ?.let { runCatching { KeySoundStyle.valueOf(it) }.getOrNull() }
                ?: defaults.keySoundStyle,
            keySoundVolume = p[KEY_SOUND_VOLUME] ?: defaults.keySoundVolume,
            keySoundCustom = KeySoundSettings(
                customId = p[KEY_SOUND_CUSTOM_ID] ?: defaults.keySoundCustom.customId,
                packId = p[KEY_SOUND_PACK_ID] ?: defaults.keySoundCustom.packId,
                playRelease = p[KEY_SOUND_RELEASE] ?: defaults.keySoundCustom.playRelease,
            ),
            popup = popupFromPrefs(p, defaults),
            colorVisionFilter = p[COLOR_VISION_FILTER]
                ?.let { runCatching { ColorVisionFilter.valueOf(it) }.getOrNull() }
                ?: defaults.colorVisionFilter,
            highContrastKeys = p[HIGH_CONTRAST_KEYS] ?: defaults.highContrastKeys,
            keyOutlines = p[KEY_OUTLINES] ?: defaults.keyOutlines,
            boldKeyLabels = p[BOLD_KEY_LABELS] ?: defaults.boldKeyLabels,
            reduceMotion = p[REDUCE_MOTION] ?: defaults.reduceMotion,
            screenReaderMode = p[SCREEN_READER_MODE]
                ?.let { runCatching { ScreenReaderMode.valueOf(it) }.getOrNull() }
                ?: defaults.screenReaderMode,
            keyDebounceMs = p[KEY_DEBOUNCE_MS] ?: defaults.keyDebounceMs,
            numberRow = p[NUMBER_ROW] ?: defaults.numberRow,
            autocorrect = p[AUTOCORRECT] ?: defaults.autocorrect,
            autocorrectConfidence = p[AUTOCORRECT_CONFIDENCE] ?: defaults.autocorrectConfidence,
            autocorrectAdaptive = p[AUTOCORRECT_ADAPTIVE] ?: defaults.autocorrectAdaptive,
            revertAutocorrectOnBackspace =
                p[REVERT_AUTOCORRECT_ON_BACKSPACE] ?: defaults.revertAutocorrectOnBackspace,
            autocorrectSkipAllCaps =
                p[AUTOCORRECT_SKIP_ALL_CAPS] ?: defaults.autocorrectSkipAllCaps,
            autoApostrophe = p[AUTO_APOSTROPHE] ?: defaults.autoApostrophe,
            autoCapitalize = p[AUTO_CAPITALIZE] ?: defaults.autoCapitalize,
            doubleSpacePeriod = p[DOUBLE_SPACE_PERIOD] ?: defaults.doubleSpacePeriod,
            doubleSpaceTab = p[DOUBLE_SPACE_TAB] ?: defaults.doubleSpaceTab,
            autoSpaceAfterPunctuation = p[AUTO_SPACE_AFTER_PUNCTUATION]
                ?: defaults.autoSpaceAfterPunctuation,
            suggestions = p[SUGGESTIONS] ?: defaults.suggestions,
            showSuggestionsInAllFields = p[SHOW_SUGGESTIONS_ALL_FIELDS]
                ?: defaults.showSuggestionsInAllFields,
            contactSuggestions = p[CONTACT_SUGGESTIONS] ?: defaults.contactSuggestions,
            contactEmailSuggestions = p[CONTACT_EMAIL_SUGGESTIONS]
                ?: defaults.contactEmailSuggestions,
            contactEmailSuggestionsInEmailFields = p[CONTACT_EMAIL_SUGGESTIONS_IN_EMAIL_FIELDS]
                ?: defaults.contactEmailSuggestionsInEmailFields,
            appNameSuggestions = p[APP_NAME_SUGGESTIONS] ?: defaults.appNameSuggestions,
            suggestionBlacklist = p[SUGGESTION_BLACKLIST] ?: defaults.suggestionBlacklist,
            inlineEmojiSearch = p[INLINE_EMOJI_SEARCH] ?: defaults.inlineEmojiSearch,
            inlineAutofill = p[INLINE_AUTOFILL] ?: defaults.inlineAutofill,
            gestureTyping = p[GESTURE_TYPING] ?: defaults.gestureTyping,
            letterSwipeAction = p[LETTER_SWIPE_ACTION]
                ?.let { runCatching { LetterSwipeAction.valueOf(it) }.getOrNull() }
                ?: defaults.letterSwipeAction,
            gesture = GestureSettings(
                spaceGlideMultiWord = p[GESTURE_SPACE_MULTI_WORD] ?: defaults.gesture.spaceGlideMultiWord,
                ambiguityPicker = p[GESTURE_AMBIGUITY_PICKER] ?: defaults.gesture.ambiguityPicker,
                apostropheKey = p[GESTURE_APOSTROPHE_KEY]
                    ?.let { runCatching { GlideApostropheKey.valueOf(it) }.getOrNull() }
                    ?: defaults.gesture.apostropheKey,
                apostropheS = p[GESTURE_APOSTROPHE_S] ?: defaults.gesture.apostropheS,
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
            ),
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
            hardwareKeyboard = HardwareKeyboardSettings(
                shortcutsEnabled = p[HW_SHORTCUTS_ENABLED] ?: defaults.hardwareKeyboard.shortcutsEnabled,
                panelNavigation = p[HW_PANEL_NAVIGATION] ?: defaults.hardwareKeyboard.panelNavigation,
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
            ),
            volumeCursor = p[VOLUME_CURSOR] ?: defaults.volumeCursor,
            volumeCursorMediaAware = p[VOLUME_CURSOR_MEDIA_AWARE] ?: defaults.volumeCursorMediaAware,
            globeAsEmoji = p[GLOBE_AS_EMOJI] ?: defaults.globeAsEmoji,
            osLanguageSwitcher = p[OS_LANGUAGE_SWITCHER] ?: defaults.osLanguageSwitcher,
            subtypeAppNameFirst = p[SUBTYPE_APP_NAME_FIRST] ?: defaults.subtypeAppNameFirst,
            perAppLanguage = PerAppLanguageSettings(
                enabled = p[PER_APP_LANGUAGE_ENABLED] ?: defaults.perAppLanguage.enabled,
                layoutByPackage = p[PER_APP_LAYOUT_MAP]?.let { decodePerAppLayouts(it) }
                    ?: defaults.perAppLanguage.layoutByPackage,
            ),
            onboardingDone = p[ONBOARDING_DONE] ?: defaults.onboardingDone,
            onboarding = OnboardingSettings(
                personaLanguages = p[ONBOARDING_PERSONA_LANGUAGES]
                    ?.let { runCatching { PersonaLanguages.valueOf(it) }.getOrNull() }
                    ?: defaults.onboarding.personaLanguages,
                personaDepth = p[ONBOARDING_PERSONA_DEPTH]
                    ?.let { runCatching { PersonaDepth.valueOf(it) }.getOrNull() }
                    ?: defaults.onboarding.personaDepth,
                personaPrivacy = p[ONBOARDING_PERSONA_PRIVACY]
                    ?.let { runCatching { PersonaPrivacy.valueOf(it) }.getOrNull() }
                    ?: defaults.onboarding.personaPrivacy,
            ),
            appUi = AppUiSettings(
                themeGalleryStyle = p[THEME_GALLERY_STYLE]
                    ?.let { runCatching { ThemeGalleryStyle.valueOf(it) }.getOrNull() }
                    ?: defaults.appUi.themeGalleryStyle,
                defaultWordlistSize = p[DEFAULT_WORDLIST_SIZE]
                    ?.let {
                        runCatching { DictionaryCatalog.DictionarySize.valueOf(it) }.getOrNull()
                    }
                    ?: defaults.appUi.defaultWordlistSize,
            ),
            toolLimits = ToolLimitSettings(
                weatherRefreshMinutes = p[WEATHER_REFRESH_MINUTES]
                    ?: defaults.toolLimits.weatherRefreshMinutes,
                wikiLinkLimit = p[WIKI_LINK_LIMIT] ?: defaults.toolLimits.wikiLinkLimit,
                qrMaxChars = p[QR_MAX_CHARS] ?: defaults.toolLimits.qrMaxChars,
                passwordSymbols = p[PASSWORD_SYMBOLS] ?: defaults.toolLimits.passwordSymbols,
            ),
            rows = RowSettings(
                symbolRowHeightDp = p[SYMBOL_ROW_HEIGHT] ?: defaults.rows.symbolRowHeightDp,
                manualModeDuration = p[MANUAL_MODE_DURATION]
                    ?.let { runCatching { ManualModeDuration.valueOf(it) }.getOrNull() }
                    ?: defaults.rows.manualModeDuration,
            ),
            conjunctBackspaceLanguages = conjunctLanguagesFromPrefs(p, layoutSelection.enabledLanguages),
            cjk = CjkSettings(
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
                hanRegion = p[CJK_HAN_REGION]
                    ?.let { runCatching { HanVariant.HanRegion.valueOf(it) }.getOrNull() }
                    ?: defaults.cjk.hanRegion,
            ),
            oneHandedMode = p[ONE_HANDED_MODE]
                ?.let { runCatching { OneHandedMode.valueOf(it) }.getOrNull() }
                ?: defaults.oneHandedMode,
            oneHanded = OneHandedSettings(
                portrait = readOneHandedProfile(p, landscape = false, defaults.oneHanded.portrait),
                landscape = readOneHandedProfile(p, landscape = true, defaults.oneHanded.landscape),
            ),
            learnFromTyping = p[LEARN_FROM_TYPING] ?: defaults.learnFromTyping,
            addWordsToSystemDictionary =
                p[ADD_WORDS_TO_SYSTEM_DICTIONARY] ?: defaults.addWordsToSystemDictionary,
            clipboard = ClipboardSettings(
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
                bottomRow = p[CLIPBOARD_BOTTOM_ROW] ?: defaults.clipboard.bottomRow,
                pinnedLast = p[CLIPBOARD_PINNED_LAST] ?: defaults.clipboard.pinnedLast,
                search = p[CLIPBOARD_SEARCH] ?: defaults.clipboard.search,
                userScreenshots = p[CLIPBOARD_USER_SCREENSHOTS] ?: defaults.clipboard.userScreenshots,
                clearAfterPasswordPaste = p[CLIPBOARD_CLEAR_AFTER_PASSWORD_PASTE]
                    ?: defaults.clipboard.clearAfterPasswordPaste,
                detectEntities = p[CLIPBOARD_DETECT_ENTITIES] ?: defaults.clipboard.detectEntities,
                phoneFormats = p[CLIPBOARD_PHONE_FORMATS] ?: seededPhoneFormats(),
                fullBleed = p[CLIPBOARD_FULL_BLEED] ?: defaults.clipboard.fullBleed,
            ),
            otp = OtpSettings(
                enabled = p[OTP_CHIP_ENABLED] ?: defaults.otp.enabled,
                numberFieldsOnly = p[OTP_NUMBER_FIELDS_ONLY] ?: defaults.otp.numberFieldsOnly,
                expiryMinutes = p[OTP_EXPIRY_MINUTES] ?: defaults.otp.expiryMinutes,
                dismissNotification = p[OTP_DISMISS_NOTIFICATION]
                    ?: defaults.otp.dismissNotification,
                perDigitEntry = p[OTP_PER_DIGIT_ENTRY] ?: defaults.otp.perDigitEntry,
            ),
            autoBackup = AutoBackupSettings(
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
            ),
            suggestionStrip = SuggestionStripSettings(
                punctuation = p[PUNCTUATION_SUGGESTIONS] ?: defaults.suggestionStrip.punctuation,
                punctuationChips = p[PUNCTUATION_CHIPS]?.takeIf { it.isNotBlank() }
                    ?: defaults.suggestionStrip.punctuationChips,
                slotCount = p[SUGGESTION_SLOT_COUNT] ?: defaults.suggestionStrip.slotCount,
                textScale = p[SUGGESTION_TEXT_SCALE] ?: defaults.suggestionStrip.textScale,
                scrollable = p[SUGGESTION_SCROLLABLE] ?: defaults.suggestionStrip.scrollable,
                chipPadding = p[SUGGESTION_CHIP_PADDING] ?: defaults.suggestionStrip.chipPadding,
                learnedWordMinCount = p[LEARNED_WORD_MIN_COUNT]
                    ?: defaults.suggestionStrip.learnedWordMinCount,
                newWordSightings = p[NEW_WORD_SIGHTINGS]
                    ?: defaults.suggestionStrip.newWordSightings,
                askBeforeLearning = p[ASK_BEFORE_LEARNING]
                    ?: defaults.suggestionStrip.askBeforeLearning,
                offerNearMissCorrections = p[OFFER_NEAR_MISS_CORRECTIONS]
                    ?: defaults.suggestionStrip.offerNearMissCorrections,
                suggestionsFirst = p[SUGGESTIONS_FIRST] ?: defaults.suggestionStrip.suggestionsFirst,
                suggestionPrimaryCenter = p[SUGGESTION_PRIMARY_CENTER]
                    ?: defaults.suggestionStrip.suggestionPrimaryCenter,
                blockOffensiveWords = p[BLOCK_OFFENSIVE_WORDS]
                    ?: defaults.suggestionStrip.blockOffensiveWords,
                contextRerank = p[CONTEXT_RERANK]
                    ?: defaults.suggestionStrip.contextRerank,
                autoSpaceAfterSuggestion = p[AUTO_SPACE_AFTER_SUGGESTION]
                    ?: defaults.suggestionStrip.autoSpaceAfterSuggestion,
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
                autocorrectSplits = p[AUTOCORRECT_SPLITS]
                    ?: defaults.suggestionStrip.autocorrectSplits,
                spellingMapOffLangs = p[SPELLING_MAP_OFF_LANGS]
                    ?: defaults.suggestionStrip.spellingMapOffLangs,
                languageDetection = p[LANGUAGE_DETECTION]
                    ?: defaults.suggestionStrip.languageDetection,
                languageDetectionStrength = p[LANGUAGE_DETECTION_STRENGTH]
                    ?.let { runCatching { LanguageDetectionStrength.valueOf(it) }.getOrNull() }
                    ?: defaults.suggestionStrip.languageDetectionStrength,
            ),
            longPressDelayMs = p[LONG_PRESS_DELAY] ?: defaults.longPressDelayMs,
            keyRepeat = KeyRepeatSettings(
                deleteMs = p[KEY_REPEAT_DELETE] ?: p[KEY_REPEAT_INTERVAL]
                    ?: defaults.keyRepeat.deleteMs,
                spaceMs = p[KEY_REPEAT_SPACE] ?: p[KEY_REPEAT_INTERVAL]
                    ?: defaults.keyRepeat.spaceMs,
                startDelayMs = p[KEY_REPEAT_START_DELAY] ?: defaults.keyRepeat.startDelayMs,
            ),
            longPressHints = p[LONG_PRESS_HINTS] ?: defaults.longPressHints,
            layoutBehavior = LayoutBehaviorSettings(
                symbolsLongPressNumpad =
                    p[SYMBOLS_LONGPRESS_NUMPAD] ?: defaults.layoutBehavior.symbolsLongPressNumpad,
                spaceSwipeDownHide =
                    p[SPACE_SWIPE_DOWN_HIDE] ?: defaults.layoutBehavior.spaceSwipeDownHide,
                spaceCursor2d = p[SPACE_CURSOR_2D] ?: defaults.layoutBehavior.spaceCursor2d,
                spaceHoldKeys = p[SPACE_HOLD_KEYS]
                    ?.split('\n')?.filter { it.isNotEmpty() }
                    ?: defaults.layoutBehavior.spaceHoldKeys,
                hintFontScale = p[HINT_FONT_SCALE] ?: defaults.layoutBehavior.hintFontScale,
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
                numberRowShiftSymbols =
                    p[NUMBER_ROW_SHIFT_SYMBOLS] ?: defaults.layoutBehavior.numberRowShiftSymbols,
                smartHitDetection =
                    p[SMART_HIT_DETECTION] ?: defaults.layoutBehavior.smartHitDetection,
                spacebarDisplay = p[SPACEBAR_DISPLAY]
                    ?.let { runCatching { SpacebarDisplay.valueOf(it) }.getOrNull() }
                    ?: defaults.layoutBehavior.spacebarDisplay,
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
                sidePadScale = p[SIDE_PAD_SCALE] ?: defaults.layoutBehavior.sidePadScale,
                splitOnlyOnLargeScreens = p[SPLIT_ONLY_LARGE]
                    ?: defaults.layoutBehavior.splitOnlyOnLargeScreens,
                shiftCapsLockMs = p[SHIFT_CAPS_LOCK_MS] ?: defaults.layoutBehavior.shiftCapsLockMs,
                showAllPopupKeys = p[SHOW_ALL_POPUP_KEYS] ?: defaults.layoutBehavior.showAllPopupKeys,
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
            ),
            rawClipboardShortcuts = p[RAW_CLIPBOARD_SHORTCUTS] ?: defaults.rawClipboardShortcuts,
            longPressLetterActions = LongPressLetterActions(
                selectAll = p[LONG_PRESS_A_SELECT_ALL] ?: defaults.longPressLetterActions.selectAll,
                copy = p[LONG_PRESS_C_COPY] ?: defaults.longPressLetterActions.copy,
                paste = p[LONG_PRESS_V_PASTE] ?: defaults.longPressLetterActions.paste,
                cut = p[LONG_PRESS_X_CUT] ?: defaults.longPressLetterActions.cut,
                undo = p[LONG_PRESS_Z_UNDO] ?: defaults.longPressLetterActions.undo,
                redo = p[LONG_PRESS_Y_REDO] ?: defaults.longPressLetterActions.redo,
                letters = p[LONG_PRESS_LETTERS] ?: defaults.longPressLetterActions.letters,
            ),
            emojiToolbar = p[EMOJI_TOOLBAR] ?: defaults.emojiToolbar,
            coloredToolIcons = p[COLORED_TOOL_ICONS] ?: defaults.coloredToolIcons,
            toolColorOverrides = decodeToolColors(p[TOOL_COLOR_OVERRIDES]),
            toolIconGradients = p[TOOL_ICON_GRADIENTS] ?: defaults.toolIconGradients,
            toolColorEndOverrides = decodeToolColors(p[TOOL_COLOR_END_OVERRIDES]),
            icons = IconSettings(
                activePackId = p[ICON_PACK_ID] ?: defaults.icons.activePackId,
                overrides = IconOverrides.decode(p[ICON_OVERRIDES]),
            ),
            incognito = p[INCOGNITO] ?: defaults.incognito,
            // Empty stored string is a valid state (everything in the toolbox),
            // distinct from never-set (defaults apply).
            toolbarTools = p[TOOLBAR_TOOLS]?.let { csv ->
                if (csv.isEmpty()) emptyList()
                else csv.split(',').mapNotNull { runCatching { ToolbarTool.valueOf(it) }.getOrNull() }
            } ?: defaults.toolbarTools,
            toolbarBehavior = ToolbarBehavior(
                enabled = p[TOOLBAR_ENABLED] ?: defaults.toolbarBehavior.enabled,
                swipeDownHide = p[TOOLBAR_SWIPE_DOWN_HIDE] ?: defaults.toolbarBehavior.swipeDownHide,
                onlyWithHardwareKeyboard =
                    p[TOOLBAR_ONLY_HW_KEYBOARD] ?: defaults.toolbarBehavior.onlyWithHardwareKeyboard,
                reverseForRtl = p[REVERSE_TOOLBAR_RTL] ?: defaults.toolbarBehavior.reverseForRtl,
                greedy = p[TOOLBAR_GREEDY] ?: defaults.toolbarBehavior.greedy,
                scrollable = p[TOOLBAR_SCROLLABLE] ?: defaults.toolbarBehavior.scrollable,
                hideWhenLocked = p[TOOLBAR_HIDE_WHEN_LOCKED] ?: defaults.toolbarBehavior.hideWhenLocked,
                toolWidthDp = p[TOOLBAR_TOOL_WIDTH] ?: defaults.toolbarBehavior.toolWidthDp,
                themesPanelBuiltIns = p[THEMES_PANEL_BUILTINS],
                placement = p[TOOLBAR_PLACEMENT]
                    ?.let { runCatching { ToolbarPlacement.valueOf(it) }.getOrNull() }
                    ?: defaults.toolbarBehavior.placement,
                holdActions = ToolHoldActions.decode(p[TOOLBAR_HOLD_ACTIONS]),
            ),
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
            emoji = EmojiSettings(
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
                usageVersion = p[EMOJI_USAGE_VERSION] ?: defaults.emoji.usageVersion,
                animated = p[EMOJI_ANIMATED] ?: defaults.emoji.animated,
                sendAsSticker = p[EMOJI_SEND_AS_STICKER] ?: defaults.emoji.sendAsSticker,
            ),
            enabledTools = ToolbarTool.entries - decodeDisabledTools(p[DISABLED_TOOLS]),
            toolboxOrder = decodeToolOrder(p[TOOLBOX_ORDER]),
            toolboxHintDismissed = p[TOOLBOX_HINT_DISMISSED] ?: defaults.toolboxHintDismissed,
            toolbox = ToolboxSettings(
                layout = p[TOOLBOX_LAYOUT]?.let { runCatching { ToolboxLayout.valueOf(it) }.getOrNull() }
                    ?: defaults.toolbox.layout,
                pillColumns = p[TOOLBOX_PILL_COLUMNS]?.coerceIn(1, 3)
                    ?: defaults.toolbox.pillColumns,
                pillFilled = p[TOOLBOX_PILL_FILLED] ?: defaults.toolbox.pillFilled,
                paginate = p[TOOLBOX_PAGINATE] ?: defaults.toolbox.paginate,
                pageSize = p[TOOLBOX_PAGE_SIZE]?.coerceIn(ToolboxPageSizeRange)
                    ?: defaults.toolbox.pageSize,
                labelSizeSp = p[TOOLBOX_LABEL_SIZE] ?: defaults.toolbox.labelSizeSp,
            ),
            flashlightAutoOff = p[FLASHLIGHT_AUTO_OFF] ?: defaults.flashlightAutoOff,
            compassShowDegrees = p[COMPASS_SHOW_DEGREES] ?: defaults.compassShowDegrees,
            compassShowQibla = p[COMPASS_SHOW_QIBLA] ?: defaults.compassShowQibla,
            levelShowAngles = p[LEVEL_SHOW_ANGLES] ?: defaults.levelShowAngles,
            redoUsesCtrlY = p[REDO_USES_CTRL_Y] ?: defaults.redoUsesCtrlY,
            moonSouthernHemisphere = p[MOON_SOUTHERN] ?: isSouthernHemisphere(deviceRegion),
            weatherFahrenheit = p[WEATHER_FAHRENHEIT] ?: defaults.weatherFahrenheit,
            weatherLatitude = p[WEATHER_LAT],
            weatherLongitude = p[WEATHER_LON],
            weatherPlaceName = p[WEATHER_PLACE] ?: defaults.weatherPlaceName,
            calendarAltOne = calendarAltFromPrefs(p, first = true),
            calendarAltTwo = calendarAltFromPrefs(p, first = false),
            calendarWeekend = p[CALENDAR_WEEKEND]?.let { Weekend.fromId(it) }
                ?: Weekend.forRegion(deviceRegion),
            hijriAdjustDays = p[HIJRI_ADJUST_DAYS] ?: defaults.hijriAdjustDays,
            handwritingStylusOnly = p[HANDWRITING_STYLUS_ONLY] ?: defaults.handwritingStylusOnly,
            handwritingCommitDelayMs = p[HANDWRITING_COMMIT_DELAY]
                ?: defaults.handwritingCommitDelayMs,
            handwritingAutoSpace = p[HANDWRITING_AUTO_SPACE] ?: defaults.handwritingAutoSpace,
            voiceBar = VoiceBarSettings(
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
                returnMode = p[VOICE_UI_RETURN_MODE] ?: defaults.voiceBar.returnMode,
                inline = p[VOICE_BAR_INLINE] ?: defaults.voiceBar.inline,
            ),
            voiceContinuous = p[VOICE_CONTINUOUS] ?: defaults.voiceContinuous,
            voiceSpokenPunctuation = p[VOICE_SPOKEN_PUNCTUATION]
                ?: defaults.voiceSpokenPunctuation,
            whisper = WhisperSettings(
                engine = p[VOICE_ENGINE] ?: defaults.whisper.engine,
                modelId = p[WHISPER_MODEL_ID] ?: defaults.whisper.modelId,
                modelByLang = p[WHISPER_MODEL_BY_LANG]?.let { decodeWhisperModelByLang(it) }
                    ?: defaults.whisper.modelByLang,
                translate = p[WHISPER_TRANSLATE] ?: defaults.whisper.translate,
            ),
            camera = CameraSettings(
                preferFront = p[CAMERA_PREFER_FRONT] ?: defaults.camera.preferFront,
                timerSeconds = p[CAMERA_TIMER_SECONDS] ?: defaults.camera.timerSeconds,
                captureMaxPx = p[CAMERA_CAPTURE_MAX_PX] ?: defaults.camera.captureMaxPx,
                mirrorFront = p[CAMERA_MIRROR_FRONT] ?: defaults.camera.mirrorFront,
                shutterSound = p[CAMERA_SHUTTER_SOUND] ?: defaults.camera.shutterSound,
                haptics = p[CAMERA_HAPTICS] ?: defaults.camera.haptics,
                saveToGallery = p[CAMERA_SAVE_TO_GALLERY] ?: defaults.camera.saveToGallery,
                fullFrame = p[CAMERA_FULL_FRAME] ?: defaults.camera.fullFrame,
            ),
            docScanSaveToGallery = p[DOC_SCAN_SAVE_TO_GALLERY] ?: defaults.docScanSaveToGallery,
            qrSaveToGallery = p[QR_SAVE_TO_GALLERY] ?: defaults.qrSaveToGallery,
            stickerSendMode = p[STICKER_SEND_MODE]
                ?.let { runCatching { MediaSendMode.valueOf(it) }.getOrNull() }
                ?: defaults.stickerSendMode,
            gifSendMode = p[GIF_SEND_MODE]
                ?.let { runCatching { MediaSendMode.valueOf(it) }.getOrNull() }
                ?: defaults.gifSendMode,
            qrSendMode = p[QR_SEND_MODE]
                ?.let { runCatching { MediaSendMode.valueOf(it) }.getOrNull() }
                ?: defaults.qrSendMode,
            dictionaryAutoLookup = p[DICTIONARY_AUTO_LOOKUP] ?: defaults.dictionaryAutoLookup,
            textEditing = TextEditingSettings(
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
                // Null when nothing is stored *or* when what is stored has no
                // usable key left; both mean the shipped arrangement.
                layout = TextEditLayoutCodec.decode(p[TEXT_EDIT_LAYOUT]),
                wrapSelectionWithPair =
                    p[WRAP_SELECTION_WITH_PAIR] ?: defaults.textEditing.wrapSelectionWithPair,
                recapitalizeSelectionWithShift = p[RECAPITALIZE_SELECTION_WITH_SHIFT]
                    ?: defaults.textEditing.recapitalizeSelectionWithShift,
                doubleSpaceWindowMs = p[DOUBLE_SPACE_WINDOW_MS]
                    ?: defaults.textEditing.doubleSpaceWindowMs,
                spaceCursorStepDp = p[SPACE_CURSOR_STEP_DP]
                    ?: defaults.textEditing.spaceCursorStepDp,
                backspaceWordStepDp = p[BACKSPACE_WORD_STEP_DP]
                    ?: defaults.textEditing.backspaceWordStepDp,
            ),
            powerSaving = PowerSavingSettings(
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
            ),
            dataSaver = dataSaverFromPrefs(p, defaults),
            numpadCalculatorLayout = p[NUMPAD_CALCULATOR_LAYOUT]
                ?: p[NUMPAD_PHONE_LAYOUT]?.not()
                ?: defaults.numpadCalculatorLayout,
            incognitoPausesClipboard = p[INCOGNITO_PAUSES_CLIPBOARD] ?: defaults.incognitoPausesClipboard,
            incognitoPausesLearning = p[INCOGNITO_PAUSES_LEARNING] ?: defaults.incognitoPausesLearning,
            autoIncognito = p[AUTO_INCOGNITO] ?: defaults.autoIncognito,
            cloudBackup = p[CloudBackup.KEY] ?: defaults.cloudBackup,
            ocrAutoSelectWords = p[OCR_AUTO_SELECT_WORDS] ?: defaults.ocrAutoSelectWords,
            qrScanHaptics = p[QR_SCAN_HAPTICS] ?: defaults.qrScanHaptics,
            qrScanAutoInsert = p[QR_SCAN_AUTO_INSERT] ?: defaults.qrScanAutoInsert,
            qrScanLinkPreviews = p[QR_SCAN_LINK_PREVIEWS] ?: defaults.qrScanLinkPreviews,
            currencyDecimals = p[CURRENCY_DECIMALS] ?: defaults.currencyDecimals,
            currencyCacheHours = p[CURRENCY_CACHE_HOURS] ?: defaults.currencyCacheHours,
            rateSources = RateSourceSettings(
                fiatProviders = p[FIAT_PROVIDERS]?.split('\n')?.filter { it.isNotEmpty() }
                    ?: defaults.rateSources.fiatProviders,
                cryptoEnabled = p[CRYPTO_ENABLED] ?: defaults.rateSources.cryptoEnabled,
                cryptoProviders = p[CRYPTO_PROVIDERS]?.split('\n')?.filter { it.isNotEmpty() }
                    ?: defaults.rateSources.cryptoProviders,
                cryptoCacheMinutes = p[CRYPTO_CACHE_MINUTES]
                    ?: defaults.rateSources.cryptoCacheMinutes,
                cryptoTickers = p[CRYPTO_TICKERS] ?: defaults.rateSources.cryptoTickers,
                cryptoDecimals = p[CRYPTO_DECIMALS] ?: defaults.rateSources.cryptoDecimals,
            ),
            grammarDebounceMs = p[GRAMMAR_DEBOUNCE_MS] ?: defaults.grammarDebounceMs,
            unitConvertLast = p[UNIT_CONVERT_LAST] ?: defaults.unitConvertLast,
            compoundUnits = p[COMPOUND_UNITS] ?: defaults.compoundUnits,
            toolboxColumns = p[TOOLBOX_COLUMNS] ?: defaults.toolboxColumns,
            translateTargetLang = p[TRANSLATE_TARGET_LANG] ?: defaults.translateTargetLang,
            grammarDialect = p[GRAMMAR_DIALECT]
                ?.let { runCatching { GrammarDialect.valueOf(it) }.getOrNull() }
                ?: defaults.grammarDialect,
            spellCheckerNoSuggestions = p[SPELL_CHECKER_NO_SUGGESTIONS]
                ?: defaults.spellCheckerNoSuggestions,
            translateApiKey = p[TRANSLATE_API_KEY] ?: defaults.translateApiKey,
            klipyApiKey = p[KLIPY_API_KEY] ?: defaults.klipyApiKey,
            braveApiKey = p[BRAVE_API_KEY] ?: defaults.braveApiKey,
            giphyApiKey = p[GIPHY_API_KEY] ?: defaults.giphyApiKey,
            gifSourceMode = p[GIF_SOURCE_MODE]
                ?.let { runCatching { GifSourceMode.valueOf(it) }.getOrNull() }
                ?: defaults.gifSourceMode,
            gifContentFilter = p[GIF_CONTENT_FILTER]
                ?.let { runCatching { GifContentFilter.valueOf(it) }.getOrNull() }
                ?: defaults.gifContentFilter,
            gifResultLimit = p[GIF_RESULT_LIMIT] ?: defaults.gifResultLimit,
            searchSafe = p[SEARCH_SAFE] ?: defaults.searchSafe,
            searchResultCount = p[SEARCH_RESULT_COUNT] ?: defaults.searchResultCount,
            wikiLanguage = p[WIKI_LANGUAGE] ?: defaults.wikiLanguage,
            wikiLinksMarkdown = p[WIKI_LINKS_MARKDOWN] ?: defaults.wikiLinksMarkdown,
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
            barOrder = p[BAR_ORDER]
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
                },
            emojiFullBleed = p[EMOJI_FULL_BLEED] ?: defaults.emojiFullBleed,
            mediaFullBleed = p[MEDIA_FULL_BLEED] ?: defaults.mediaFullBleed,
            modeToolOrderEdits = p[MODE_TOOL_ORDER_EDITS] ?: defaults.modeToolOrderEdits,
            modeToolOrderHintSeen = p[MODE_TOOL_ORDER_HINT] ?: defaults.modeToolOrderHintSeen,
            keyboardModes = p[KEYBOARD_MODES]?.let { KeyboardModeCodec.decodeList(it) }
                ?: defaults.keyboardModes,
            smartSuggestions = p[SMART_SUGGESTIONS] ?: defaults.smartSuggestions,
            smartCalc = p[SMART_CALC] ?: defaults.smartCalc,
            smartCurrency = p[SMART_CURRENCY] ?: defaults.smartCurrency,
            smartUnits = p[SMART_UNITS] ?: defaults.smartUnits,
            smartToolKeywords = p[SMART_TOOL_KEYWORDS] ?: defaults.smartToolKeywords,
            smartChips = SmartChipSettings(
                dates = p[SMART_CHIP_DATES] ?: defaults.smartChips.dates,
                weather = p[SMART_CHIP_WEATHER] ?: defaults.smartChips.weather,
                lookups = p[SMART_CHIP_LOOKUPS] ?: defaults.smartChips.lookups,
                intents = p[SMART_CHIP_INTENTS] ?: defaults.smartChips.intents,
                gifs = p[SMART_CHIP_GIFS] ?: defaults.smartChips.gifs,
            ),
            toolKeywords = p[TOOL_KEYWORDS] ?: defaults.toolKeywords,
            toolKeywordCase = p[TOOL_KEYWORD_CASE] ?: defaults.toolKeywordCase,
            calcDegrees = p[CALC_DEGREES] ?: defaults.calcDegrees,
            calcPrecision = p[CALC_PRECISION] ?: defaults.calcPrecision,
            currencyFrom = p[CURRENCY_FROM] ?: defaults.currencyFrom,
            currencyTo = p[CURRENCY_TO] ?: defaults.currencyTo,
            // The keys stay flat across the grouping, so a user's stored
            // generator settings survive the refactor untouched.
            passwordGenerator = PasswordGeneratorSettings(
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
            ),
            typingTest = TypingTestSettings(
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
            ),
            typingStatsEnabled = p[TYPING_STATS_ENABLED] ?: defaults.typingStatsEnabled,
            statsVersion = p[STATS_VERSION] ?: defaults.statsVersion,
            qrSizePx = p[QR_SIZE_PX] ?: defaults.qrSizePx,
            qrEcc = p[QR_ECC]?.let { runCatching { QrEccLevel.valueOf(it) }.getOrNull() }
                ?: defaults.qrEcc,
            ai = AiSettings(
                provider = p[AI_PROVIDER]
                    ?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() }
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
                beforeCursorChars = p[AI_BEFORE_CURSOR_CHARS]
                    ?: defaults.ai.beforeCursorChars,
            ),
            launcher = LauncherToolSettings(
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
            ),
        )
    }

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

    suspend fun setWeatherFahrenheit(value: Boolean) =
        editPrefs { it[WEATHER_FAHRENHEIT] = value }

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

    suspend fun setBackspaceWordStepDp(value: Int) =
        editPrefs { it[BACKSPACE_WORD_STEP_DP] = value.coerceIn(32, 120) }

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

    suspend fun setQrScanHaptics(value: Boolean) =
        editPrefs { it[QR_SCAN_HAPTICS] = value }

    suspend fun setQrScanAutoInsert(value: Boolean) =
        editPrefs { it[QR_SCAN_AUTO_INSERT] = value }

    suspend fun setQrScanLinkPreviews(value: Boolean) =
        editPrefs { it[QR_SCAN_LINK_PREVIEWS] = value }

    suspend fun setCurrencyDecimals(value: Int) =
        editPrefs { it[CURRENCY_DECIMALS] = value.coerceIn(0, 6) }

    suspend fun setCurrencyCacheHours(value: Int) =
        editPrefs { it[CURRENCY_CACHE_HOURS] = value.coerceIn(1, 48) }

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

    suspend fun setToolbarPlacement(value: ToolbarPlacement) =
        editPrefs { it[TOOLBAR_PLACEMENT] = value.name }

    /**
     * Sets or clears one tool's press-and-hold action. Null puts that tool back
     * to opening its own settings page.
     */
    suspend fun setToolHoldAction(tool: ToolbarTool, action: ToolbarTool?) =
        editPrefs { prefs ->
            val current = ToolHoldActions.decode(prefs[TOOLBAR_HOLD_ACTIONS]).toMutableMap()
            if (action == null || action == tool) current.remove(tool) else current[tool] = action
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
     */
    suspend fun setActiveLayoutId(id: String) =
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
     */
    suspend fun autoPairRomanizedSecondaries(): List<Pair<String, String>> {
        val current = settings.first()
        // Off means a link the user removed by hand stays removed, instead of
        // coming back the next time any language is added.
        if (!current.autoPairRomanized) return emptyList()
        val result = RomanizedPairing.autoPair(
            current.enabledLanguages,
            current.secondaryLanguages,
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
            // to the first remaining stop. (A null active is a pre-registry
            // install still translating `input_mode` on read; leave it alone.)
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
     * `resolveLayouts` splices both back in, so an edited BÉPO restores exactly
     * like an edited QWERTY does, and stripping its references would switch off
     * a layout that is still there.
     */
    suspend fun deleteCustomLayout(id: String) =
        editPrefs { prefs ->
            val current = prefs[CUSTOM_LAYOUTS]?.let { LayoutCodec.decodeList(it) }.orEmpty()
            prefs[CUSTOM_LAYOUTS] = LayoutCodec.encodeList(current.filter { it.id != id })
            if (BuiltInLayouts.byId(id) != null || AssetLayouts.byId(id) != null) return@editPrefs
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
     */
    suspend fun setVariantSizing(
        variant: ScreenVariant,
        keyHeightDp: Int?,
        numberRowHeightDp: Int?,
        bottomPaddingDp: Int?,
    ) = editPrefs { prefs ->
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

    suspend fun setVariantSidePadScale(variant: ScreenVariant, value: Float?) =
        editVariantOnly(
            variant,
            sidePadScaleKey(variant),
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
        return KeyPopupSettings(
            enabled = p[KEY_POPUP] ?: defaults.popup.enabled,
            minDurationMs = p[KEY_POPUP_MIN_DURATION] ?: defaults.popup.minDurationMs,
            maxDurationMs = p[KEY_POPUP_MAX_DURATION] ?: defaults.popup.maxDurationMs,
            onKey = onKey,
            inNumericFields = p[KEY_POPUP_IN_NUMERIC] ?: defaults.popup.inNumericFields,
            fontScale = p[POPUP_FONT_SCALE] ?: defaults.popup.fontScale,
            heightDp = if (onKey) {
                p[KEY_POPUP_HEIGHT] ?: defaults.popup.heightDp
            } else {
                p[KEY_POPUP_FLOATING_HEIGHT] ?: defaults.popup.floatingHeightDp
            },
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
            currencyRates = p.policy(DS_CURRENCY_RATES, d.currencyRates),
            addonRefresh = p.policy(DS_ADDON_REFRESH, d.addonRefresh),
            mediaSearch = p.policy(DS_MEDIA_SEARCH, d.mediaSearch),
            webSearch = p.policy(DS_WEB_SEARCH, d.webSearch),
            animatedEmoji = p.policy(DS_ANIMATED_EMOJI, d.animatedEmoji),
            downloads = p.policy(DS_DOWNLOADS, legacyDownloads),
            cloudAi = p.policy(DS_CLOUD_AI, d.cloudAi),
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
    ): String {
        val prefs = context.dataStore.data.first()
        val out = LinkedHashMap<ConfigBackup.Section, JsonElement>()
        if (ConfigBackup.Section.SETTINGS in sections) {
            out[ConfigBackup.Section.SETTINGS] =
                SettingsBackup.encodeSettings(
                    prefs,
                    includeSecrets,
                    exclude = SettingsBackup.THEME_KEYS + SettingsBackup.TRANSIENT_KEYS,
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
            readStore("learning/user_lexicon.json")?.let { out[ConfigBackup.Section.DICTIONARY] = it }
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
        if (ConfigBackup.Section.ADDONS in sections) {
            // The repository list only. Cached manifests are re-fetched, and
            // the installed-addon records point at local ids that mean nothing
            // on another device.
            readStore("addons/repos.json")?.let { out[ConfigBackup.Section.ADDONS] = it }
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
            if (writeStore("learning/user_lexicon.json", obj)) {
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

    suspend fun setAnimatedEmoji(value: Boolean) =
        editPrefs { it[EMOJI_ANIMATED] = value }

    suspend fun setSendEmojiAsSticker(value: Boolean) =
        editPrefs { it[EMOJI_SEND_AS_STICKER] = value }

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

    /** 0 is the automatic wrap; anything else is clamped into 3..11. */
    suspend fun setAlternatesColumns(value: Int) =
        editPrefs {
            it[ALTERNATES_COLUMNS] = if (value <= 0) 0 else value.coerceIn(3, 11)
        }

    suspend fun setAlternatesNearestFirst(value: Boolean) =
        editPrefs { it[ALTERNATES_NEAREST_FIRST] = value }

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

    suspend fun setWrapSelectionWithPair(value: Boolean) =
        editPrefs { it[WRAP_SELECTION_WITH_PAIR] = value }

    /**
     * Stores the text-editing panel's grid. Null — or a layout with nothing left
     * in it — clears the key, which puts the panel back to
     * [DefaultTextEditLayout]; that is what the editor's Reset does.
     */
    suspend fun setTextEditLayout(value: TextEditLayout?) =
        editPrefs { prefs ->
            val repaired = value?.let(TextEditLayoutCodec::repair)
            if (repaired == null) {
                prefs.remove(TEXT_EDIT_LAYOUT)
            } else {
                prefs[TEXT_EDIT_LAYOUT] = TextEditLayoutCodec.encode(repaired)
            }
        }

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

    suspend fun setBlockOffensiveWords(value: Boolean) =
        editPrefs { it[BLOCK_OFFENSIVE_WORDS] = value }

    suspend fun setContextRerank(value: Boolean) =
        editPrefs { it[CONTEXT_RERANK] = value }

    suspend fun setLanguageDetection(value: Boolean) =
        editPrefs { it[LANGUAGE_DETECTION] = value }

    suspend fun setLanguageDetectionStrength(value: LanguageDetectionStrength) =
        editPrefs { it[LANGUAGE_DETECTION_STRENGTH] = value.name }

    suspend fun setNumberRowCorrections(value: Boolean) =
        editPrefs { it[NUMBER_ROW_CORRECTIONS] = value }

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

    suspend fun setSidePadScale(value: Float) =
        editPrefs { it[SIDE_PAD_SCALE] = value.coerceIn(SidePadScaleRange.start, SidePadScaleRange.endInclusive) }

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
     * the single overlap, drawn on both screens and reset by both.
     */
    suspend fun resetAppearance() = editPrefs {
        it.remove(KEY_CORNER_RADIUS)
        it.remove(FONT_SCALE)
        it.remove(HINT_FONT_SCALE)
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
        it.remove(TOOL_CIRCLE_RADIUS)
        it.remove(TOOL_SHAPE)
        it.remove(TOOLBAR_TOOL_WIDTH)
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

    suspend fun setAiBeforeCursorChars(value: Int) =
        editPrefs { it[AI_BEFORE_CURSOR_CHARS] = value.coerceIn(500, 32_000) }

    suspend fun setShiftCapsLockMs(value: Int) =
        editPrefs { it[SHIFT_CAPS_LOCK_MS] = value.coerceIn(ShiftCapsLockMsRange.first, ShiftCapsLockMsRange.last) }

    suspend fun setShowAllPopupKeys(value: Boolean) =
        editPrefs { it[SHOW_ALL_POPUP_KEYS] = value }

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

    suspend fun setContactSuggestions(value: Boolean) =
        editPrefs { it[CONTACT_SUGGESTIONS] = value }

    suspend fun setContactEmailSuggestions(value: Boolean) =
        editPrefs { it[CONTACT_EMAIL_SUGGESTIONS] = value }

    suspend fun setContactEmailSuggestionsInEmailFields(value: Boolean) =
        editPrefs { it[CONTACT_EMAIL_SUGGESTIONS_IN_EMAIL_FIELDS] = value }

    suspend fun setAppNameSuggestions(value: Boolean) =
        editPrefs { it[APP_NAME_SUGGESTIONS] = value }

    /** Adds a word to the never-suggest blacklist (lowercased, trimmed). */
    suspend fun addSuggestionBlacklistWord(word: String) {
        val normalized = word.trim().lowercase()
        if (normalized.isEmpty()) return
        editPrefs {
            it[SUGGESTION_BLACKLIST] = (it[SUGGESTION_BLACKLIST].orEmpty() + normalized)
        }
    }

    /** Removes a word from the never-suggest blacklist. */
    suspend fun removeSuggestionBlacklistWord(word: String) {
        val normalized = word.trim().lowercase()
        editPrefs {
            it[SUGGESTION_BLACKLIST] = (it[SUGGESTION_BLACKLIST].orEmpty() - normalized)
        }
    }

    /** Empties the blacklist in one edit; per-word removal is the only other way out. */
    suspend fun clearSuggestionBlacklist() =
        editPrefs { it.remove(SUGGESTION_BLACKLIST) }

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

    suspend fun setGestureAmbiguityPicker(value: Boolean) =
        editPrefs { it[GESTURE_AMBIGUITY_PICKER] = value }

    suspend fun setGestureApostropheKey(value: GlideApostropheKey) =
        editPrefs { it[GESTURE_APOSTROPHE_KEY] = value.name }

    suspend fun setGestureApostropheS(value: Boolean) =
        editPrefs { it[GESTURE_APOSTROPHE_S] = value }

    suspend fun setGestureAutoSpace(value: Boolean) =
        editPrefs { it[GESTURE_AUTO_SPACE] = value }

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

    suspend fun setSpaceSwipeDownHide(value: Boolean) =
        editPrefs { it[SPACE_SWIPE_DOWN_HIDE] = value }

    suspend fun setSpaceCursor2d(value: Boolean) =
        editPrefs { it[SPACE_CURSOR_2D] = value }

    suspend fun setHintFontScale(value: Float) =
        editPrefs { it[HINT_FONT_SCALE] = value.coerceIn(0.5f, 2.0f) }

    suspend fun setNumberRowShiftSymbols(value: Boolean) =
        editPrefs { it[NUMBER_ROW_SHIFT_SYMBOLS] = value }

    suspend fun setSmartHitDetection(value: Boolean) =
        editPrefs { it[SMART_HIT_DETECTION] = value }

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

    suspend fun setDefaultWordlistSize(value: DictionaryCatalog.DictionarySize) =
        editPrefs { it[DEFAULT_WORDLIST_SIZE] = value.name }

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

    suspend fun setManualModeDuration(value: ManualModeDuration) =
        editPrefs { it[MANUAL_MODE_DURATION] = value.name }

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
        context.dataStore.edit { it[PINYIN_FUZZY] = value }

    suspend fun setPinyinFuzzyPair(id: String, on: Boolean) = context.dataStore.edit { p ->
        val current = p[PINYIN_FUZZY_PAIRS] ?: PinyinFuzzy.ALL_PAIRS
        p[PINYIN_FUZZY_PAIRS] = if (on) current + id else current - id
    }

    suspend fun resetPinyinFuzzyPairs() =
        context.dataStore.edit { it.remove(PINYIN_FUZZY_PAIRS) }

    suspend fun setPinyinDoublePinyin(value: DoublePinyinScheme) =
        context.dataStore.edit { it[PINYIN_DOUBLE_PINYIN] = value.name }

    suspend fun setCjkTraditionalOutput(value: Boolean) =
        context.dataStore.edit { it[CJK_TRADITIONAL_OUTPUT] = value }

    suspend fun setJyutpingLazy(value: Boolean) =
        context.dataStore.edit { it[JYUTPING_LAZY] = value }

    suspend fun setCjkHanRegion(value: HanVariant.HanRegion) =
        context.dataStore.edit { it[CJK_HAN_REGION] = value.name }

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

    /** Padding either side of a suggestion word, in dp; see [SuggestionStripSettings.chipPadding]. */
    suspend fun setSuggestionChipPadding(value: Int) =
        editPrefs { it[SUGGESTION_CHIP_PADDING] = value.coerceIn(0, 24) }

    suspend fun setClipboardBottomRow(value: Boolean) =
        editPrefs { it[CLIPBOARD_BOTTOM_ROW] = value }

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

    suspend fun setOtpChipEnabled(value: Boolean) =
        editPrefs { it[OTP_CHIP_ENABLED] = value }

    suspend fun setOtpNumberFieldsOnly(value: Boolean) =
        editPrefs { it[OTP_NUMBER_FIELDS_ONLY] = value }

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

    suspend fun setLongPressDelayMs(value: Int) =
        editPrefs { it[LONG_PRESS_DELAY] = value.coerceIn(150, 700) }

    suspend fun setDeleteRepeatIntervalMs(value: Int) =
        editPrefs { it[KEY_REPEAT_DELETE] = value.coerceIn(20, 200) }

    suspend fun setSpaceRepeatIntervalMs(value: Int) =
        editPrefs { it[KEY_REPEAT_SPACE] = value.coerceIn(20, 200) }

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

    suspend fun setGrammarDialect(value: GrammarDialect) =
        editPrefs { it[GRAMMAR_DIALECT] = value.name }

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

    /** Adds the set or replaces the stored set with the same id. */
    suspend fun upsertSymbolSet(set: SymbolSet) =
        editPrefs { prefs ->
            val current = prefs[CUSTOM_SYMBOL_SETS]?.let { SymbolSetCodec.decodeList(it) }
                .orEmpty()
            val next = current.filter { it.id != set.id } + set
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
