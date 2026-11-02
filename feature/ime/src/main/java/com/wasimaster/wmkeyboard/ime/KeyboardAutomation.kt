package com.wasimaster.wmkeyboard.ime

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.settings.AutoBackupScheduler
import com.wasimaster.wmkeyboard.core.settings.AutomationExport
import com.wasimaster.wmkeyboard.core.settings.BackupPause
import com.wasimaster.wmkeyboard.core.settings.sync.SyncScheduler
import com.wasimaster.wmkeyboard.core.settings.AutomationPermission
import com.wasimaster.wmkeyboard.core.settings.KeyboardMode
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.theme.BuiltInThemes
import com.wasimaster.wmkeyboard.core.theme.DEFAULT_THEME_ID
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.flattenedThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Controlling the keyboard from outside it: `adb shell am broadcast`, Tasker,
 * MacroDroid, Automate, anything that can send an intent.
 *
 * Nothing gets through unless the user turned on "Let other apps control the
 * keyboard", and then only what they allowed on the Automation screen, one
 * [AutomationPermission] per row (see
 * [com.wasimaster.wmkeyboard.core.settings.AutomationSettings]). The receiver
 * checks that on every intent, so switching a row off takes effect on the next
 * one.
 *
 * Most actions are settings writes and work whether or not the keyboard is
 * running; the running keyboard picks the change up the way it picks up one
 * made in the settings app. A few act on the keyboard itself (a mode, a tool,
 * typing) and need it running, through [host].
 */
object KeyboardAutomation {

    /** What the receiver needs from a running keyboard. */
    interface Host {
        /** The settings the keyboard is running on, fresher than the store. */
        val settings: KeyboardSettings

        /** The layout on screen, which a field's own request can make differ from the stored one. */
        val layoutId: String

        /** Switch exactly as the 🌐 key does. */
        fun selectLayout(layoutId: String)

        /** Apply a mode as the Modes tool does; null goes back to automatic. */
        fun selectMode(modeId: String?)

        /** Bring the keyboard up, as the notification's Show button does. */
        fun showKeyboard()

        /** Pin or unpin, exactly as the toolbar's own toggle does. */
        fun setPinned(pinned: Boolean)

        /** Open [tool]'s panel; null when it opened, else why not. */
        fun openTool(tool: ToolbarTool): String?

        /** Add [word] to the personal dictionary as if typed and added by hand. */
        fun addWord(word: String)

        /** Type [text] into the focused field; null when it went in, else why not. */
        fun typeText(text: String): String?

        /** Type the snippet [name] names (its trigger, else its label); null when it went in, else why not. */
        fun typeSnippet(name: String): String?

        /**
         * Open resize mode, as the Resize tool does, unless it is open already.
         * Not an automation action: the settings app's Resize by dragging row
         * (#912) asks for it once its test field has the keyboard up.
         */
        fun startResize()
    }

    /** The running service, or null. Same contract as [KeyboardControls.host]. */
    @Volatile
    var host: Host? = null

    private const val PREFIX = "com.wasimaster.wmkeyboard.action."

    /** Switch to the layout named by [EXTRA_LAYOUT]: its id, or its name. */
    const val ACTION_SET_LAYOUT = PREFIX + "SET_LAYOUT"

    /** Switch to a layout of the language named by [EXTRA_LANGUAGE]. */
    const val ACTION_SET_LANGUAGE = PREFIX + "SET_LANGUAGE"

    /** One step forward through the switch order, as the 🌐 key's plain cycle. */
    const val ACTION_NEXT_LAYOUT = PREFIX + "NEXT_LAYOUT"

    /** One step back through the switch order. */
    const val ACTION_PREVIOUS_LAYOUT = PREFIX + "PREVIOUS_LAYOUT"

    /** Report the enabled layouts in the broadcast's result data. Changes nothing. */
    const val ACTION_LIST_LAYOUTS = PREFIX + "LIST_LAYOUTS"

    /** Apply the mode named by [EXTRA_MODE], or `auto` to go back to automatic. */
    const val ACTION_SET_MODE = PREFIX + "SET_MODE"

    /** Pick the theme named by [EXTRA_THEME]. */
    const val ACTION_SET_THEME = PREFIX + "SET_THEME"

    /** Bring the keyboard up over the focused field. */
    const val ACTION_SHOW_KEYBOARD = PREFIX + "SHOW_KEYBOARD"

    /** Keep the keyboard on screen ([EXTRA_ENABLED] true) or stop. */
    const val ACTION_SET_PINNED = PREFIX + "SET_PINNED"

    /** The floating button on ([EXTRA_ENABLED] true) or off; toggles without it. */
    const val ACTION_SET_FLOATING_BUTTON = PREFIX + "SET_FLOATING_BUTTON"

    /** Key sound on or off. */
    const val ACTION_SET_KEY_SOUND = PREFIX + "SET_KEY_SOUND"

    /** Haptics on or off. */
    const val ACTION_SET_HAPTICS = PREFIX + "SET_HAPTICS"

    /** Power saving's manual switch on or off. */
    const val ACTION_SET_POWER_SAVING = PREFIX + "SET_POWER_SAVING"

    /** Data saver's manual switch on or off. */
    const val ACTION_SET_DATA_SAVER = PREFIX + "SET_DATA_SAVER"

    /** Normal, one-handed left or right, split or floating; see [Position]. */
    const val ACTION_SET_POSITION = PREFIX + "SET_POSITION"

    /** Open the panel of the tool named by [EXTRA_TOOL]. */
    const val ACTION_OPEN_TOOL = PREFIX + "OPEN_TOOL"

    /** Incognito on or off. Each direction is its own permission. */
    const val ACTION_SET_INCOGNITO = PREFIX + "SET_INCOGNITO"

    /** Add [EXTRA_WORD] to the personal dictionary. */
    const val ACTION_ADD_WORD = PREFIX + "ADD_WORD"

    /** Never suggest [EXTRA_WORD], in every language or in [EXTRA_LANGUAGE] only. */
    const val ACTION_BLOCK_WORD = PREFIX + "BLOCK_WORD"

    /** Back up to the automatic backup's destination now. */
    const val ACTION_BACKUP_NOW = PREFIX + "BACKUP_NOW"

    /**
     * Pause the scheduled backups for [EXTRA_HOURS] hours, or until
     * [ACTION_RESUME_BACKUP] when it is missing. Locations and schedule stay.
     */
    const val ACTION_PAUSE_BACKUP = PREFIX + "PAUSE_BACKUP"

    /** End a pause [ACTION_PAUSE_BACKUP] or the settings screen set. */
    const val ACTION_RESUME_BACKUP = PREFIX + "RESUME_BACKUP"

    /** [ACTION_PAUSE_BACKUP] for the scheduled sync passes. */
    const val ACTION_PAUSE_SYNC = PREFIX + "PAUSE_SYNC"

    /** [ACTION_RESUME_BACKUP] for sync, which also pulls straight away. */
    const val ACTION_RESUME_SYNC = PREFIX + "RESUME_SYNC"

    /** Write [EXTRA_SECTIONS] to the file [AutomationExport] keeps in the folder the user picked. */
    const val ACTION_EXPORT_DATA = PREFIX + "EXPORT_DATA"

    /** Type [EXTRA_TEXT], or the snippet [EXTRA_SNIPPET] names, into the focused field. */
    const val ACTION_TYPE_TEXT = PREFIX + "TYPE_TEXT"

    /**
     * Sent by the keyboard, not to it: the layout on screen changed. Carries
     * [EXTRA_LAYOUT], [EXTRA_LANGUAGE] and [EXTRA_NAME]. Only while
     * [AutomationPermission.LAYOUT_EVENTS] is allowed.
     */
    const val EVENT_LAYOUT_CHANGED = "com.wasimaster.wmkeyboard.event.LAYOUT_CHANGED"

    const val EXTRA_LAYOUT = "layout"
    const val EXTRA_LANGUAGE = "language"
    const val EXTRA_NAME = "name"
    const val EXTRA_MODE = "mode"
    const val EXTRA_THEME = "theme"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_POSITION = "position"
    const val EXTRA_TOOL = "tool"
    const val EXTRA_WORD = "word"
    const val EXTRA_TEXT = "text"
    const val EXTRA_SNIPPET = "snippet"
    const val EXTRA_HOURS = "hours"
    const val EXTRA_SECTIONS = "sections"

    /** [EXTRA_MODE] value that clears a hand-picked mode. */
    const val MODE_AUTO = "auto"

    /** Longest [EXTRA_TEXT] typed in one go; a mistake bigger than this is not a paste anyone meant. */
    const val MAX_TEXT_LENGTH = 10_000

    /**
     * What each action needs. [ACTION_SET_INCOGNITO] is absent: which of its
     * two permissions it needs depends on the direction, which only the
     * request and the current state decide.
     */
    val permissions: Map<String, AutomationPermission> = mapOf(
        ACTION_SET_LAYOUT to AutomationPermission.LAYOUT,
        ACTION_SET_LANGUAGE to AutomationPermission.LAYOUT,
        ACTION_NEXT_LAYOUT to AutomationPermission.LAYOUT,
        ACTION_PREVIOUS_LAYOUT to AutomationPermission.LAYOUT,
        ACTION_LIST_LAYOUTS to AutomationPermission.LAYOUT,
        ACTION_SET_MODE to AutomationPermission.MODE,
        ACTION_SET_THEME to AutomationPermission.THEME,
        ACTION_SHOW_KEYBOARD to AutomationPermission.SHOW_PIN,
        ACTION_SET_PINNED to AutomationPermission.SHOW_PIN,
        ACTION_SET_FLOATING_BUTTON to AutomationPermission.SHOW_PIN,
        ACTION_SET_KEY_SOUND to AutomationPermission.FEEDBACK,
        ACTION_SET_HAPTICS to AutomationPermission.FEEDBACK,
        ACTION_SET_POWER_SAVING to AutomationPermission.SAVERS,
        ACTION_SET_DATA_SAVER to AutomationPermission.SAVERS,
        ACTION_SET_POSITION to AutomationPermission.POSITION,
        ACTION_OPEN_TOOL to AutomationPermission.OPEN_TOOL,
        ACTION_ADD_WORD to AutomationPermission.WORDS,
        ACTION_BLOCK_WORD to AutomationPermission.WORDS,
        ACTION_BACKUP_NOW to AutomationPermission.BACKUP,
        ACTION_PAUSE_BACKUP to AutomationPermission.BACKUP_PAUSE,
        ACTION_RESUME_BACKUP to AutomationPermission.BACKUP_PAUSE,
        ACTION_PAUSE_SYNC to AutomationPermission.BACKUP_PAUSE,
        ACTION_RESUME_SYNC to AutomationPermission.BACKUP_PAUSE,
        ACTION_EXPORT_DATA to AutomationPermission.EXPORT_DATA,
        ACTION_TYPE_TEXT to AutomationPermission.TYPE_TEXT,
    )

    /** Every action the receiver answers; the manifest's filter lists exactly these. */
    val actions: Set<String> = permissions.keys + ACTION_SET_INCOGNITO

    /** What [ACTION_SET_INCOGNITO] needs to go to [on]. */
    fun incognitoPermission(on: Boolean): AutomationPermission =
        if (on) AutomationPermission.INCOGNITO_ON else AutomationPermission.INCOGNITO_OFF

    /** What a layout request comes to, before anything is switched. */
    sealed interface Outcome {
        /** Move to [layoutId]. */
        data class Switch(val layoutId: String) : Outcome

        /** Already there; nothing to do. */
        data class Unchanged(val layoutId: String) : Outcome

        /** The request names nothing that is turned on. [reason] goes back to the sender. */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Decides where a layout [action] goes, from the enabled layouts in
     * [enabled] and the one on screen, [current]. [recent] breaks the tie when a
     * language has several layouts turned on: the one last used wins, as it
     * would if the user had switched there themselves.
     *
     * Only enabled layouts are ever an answer. That is the same set the 🌐 key
     * and the spacebar swipe walk, so an automation can never put the keyboard
     * on a layout the user could not have reached by hand.
     */
    fun resolve(
        action: String,
        layout: String?,
        language: String?,
        enabled: List<String>,
        current: String,
        recent: List<String>,
        customs: List<LayoutSpec>,
    ): Outcome {
        if (enabled.isEmpty()) return Outcome.Failed("no layouts are turned on")
        val target = when (action) {
            ACTION_SET_LAYOUT -> {
                val wanted = layout?.trim().orEmpty()
                if (wanted.isEmpty()) return Outcome.Failed("missing extra \"$EXTRA_LAYOUT\"")
                matchLayout(wanted, enabled, customs)
                    ?: return Outcome.Failed("no enabled layout is called \"$wanted\"")
            }
            ACTION_SET_LANGUAGE -> {
                val wanted = language?.trim().orEmpty()
                if (wanted.isEmpty()) return Outcome.Failed("missing extra \"$EXTRA_LANGUAGE\"")
                val ofLanguage = layoutsOfLanguage(wanted, enabled, customs)
                if (ofLanguage.isEmpty()) {
                    return Outcome.Failed("no enabled layout is for the language \"$wanted\"")
                }
                if (current in ofLanguage) return Outcome.Unchanged(current)
                recent.firstOrNull { it in ofLanguage } ?: ofLanguage.first()
            }
            ACTION_NEXT_LAYOUT, ACTION_PREVIOUS_LAYOUT -> {
                val step = if (action == ACTION_NEXT_LAYOUT) 1 else -1
                val at = enabled.indexOf(current)
                when {
                    at >= 0 -> enabled[(at + step).mod(enabled.size)]
                    // On a layout outside the ring (a field asked for it): enter
                    // the ring at the end the step points into.
                    step > 0 -> enabled.first()
                    else -> enabled.last()
                }
            }
            else -> return Outcome.Failed("unknown action $action")
        }
        return if (target == current) Outcome.Unchanged(target) else Outcome.Switch(target)
    }

    /**
     * An enabled layout by id, then by id or name ignoring case. A name two
     * enabled layouts share picks neither: guessing wrong is worse than saying
     * so.
     */
    private fun matchLayout(wanted: String, enabled: List<String>, customs: List<LayoutSpec>): String? {
        if (wanted in enabled) return wanted
        enabled.singleOrNull { it.equals(wanted, ignoreCase = true) }?.let { return it }
        return enabled.filter { resolveLayout(customs, it).name.equals(wanted, ignoreCase = true) }
            .singleOrNull()
    }

    /**
     * The enabled layouts whose language [wanted] names, in switch order. A
     * language is named by its id (`en`, `bn_rom`, `sr-Cyrl`), its locale tag
     * (`en-US`), or its name in English or in itself; failing all of those, a
     * locale tag's first part (`fr` out of `fr-CA`).
     */
    private fun layoutsOfLanguage(wanted: String, enabled: List<String>, customs: List<LayoutSpec>): List<String> {
        val key = normalizeTag(wanted)
        val languages = enabled.associateWith { resolveLayout(customs, it).language() }
        val exact = enabled.filter { id ->
            val lang = languages.getValue(id)
            normalizeTag(lang.id) == key ||
                normalizeTag(lang.localeTag) == key ||
                lang.englishName.equals(wanted, ignoreCase = true) ||
                lang.displayName.equals(wanted, ignoreCase = true)
        }
        if (exact.isNotEmpty()) return exact
        val primary = key.substringBefore('-')
        return enabled.filter { normalizeTag(languages.getValue(it).id) == primary }
    }

    private fun normalizeTag(tag: String): String = tag.trim().lowercase().replace('_', '-')

    /**
     * The enabled layouts as `id<TAB>language<TAB>name` lines, the one on
     * screen marked with `*`.
     */
    fun describe(enabled: List<String>, current: String, customs: List<LayoutSpec>): String =
        enabled.joinToString("\n") { id ->
            val spec = resolveLayout(customs, id)
            val mark = if (id == current) "*" else " "
            "$mark $id\t${spec.language().id}\t${spec.name}"
        }

    /**
     * The mode [wanted] names, by id then by name, ignoring case both times;
     * [MODE_AUTO] for automatic. Null when it names none, or when a name is
     * shared, for the same reason [matchLayout] refuses one.
     */
    fun matchMode(wanted: String, modes: List<KeyboardMode>): String? {
        val key = wanted.trim()
        if (key.equals(MODE_AUTO, ignoreCase = true)) return MODE_AUTO
        modes.firstOrNull { it.id == key }?.let { return it.id }
        modes.singleOrNull { it.id.equals(key, ignoreCase = true) }?.let { return it.id }
        return modes.filter { it.name.equals(key, ignoreCase = true) }.singleOrNull()?.id
    }

    /**
     * The theme [wanted] names: `default`, or any built-in or custom theme
     * (variants included) by id, then by stored name, ignoring case. A custom
     * theme wins a name it shares with a built-in, as it wins an id.
     */
    fun matchTheme(wanted: String, customs: List<ThemeSpec>): String? {
        val key = wanted.trim()
        if (key.equals(DEFAULT_THEME_ID, ignoreCase = true)) return DEFAULT_THEME_ID
        val custom = customs.flattenedThemes()
        val builtIn = BuiltInThemes.flattenedThemes()
        for (pool in listOf(custom, builtIn)) {
            pool.firstOrNull { it.id == key }?.let { return it.id }
        }
        for (pool in listOf(custom, builtIn)) {
            pool.singleOrNull { it.id.equals(key, ignoreCase = true) }?.let { return it.id }
            pool.filter { it.name.equals(key, ignoreCase = true) }.singleOrNull()?.let { return it.id }
        }
        return null
    }

    /** Where [ACTION_SET_POSITION] puts the keyboard. Each clears the others. */
    enum class Position(val oneHanded: OneHandedMode, val split: Boolean, val floating: Boolean) {
        NORMAL(OneHandedMode.OFF, split = false, floating = false),
        LEFT(OneHandedMode.LEFT, split = false, floating = false),
        RIGHT(OneHandedMode.RIGHT, split = false, floating = false),
        SPLIT(OneHandedMode.OFF, split = true, floating = false),
        FLOATING(OneHandedMode.OFF, split = false, floating = true),
        ;

        companion object {
            fun parse(value: String?): Position? =
                entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
        }
    }

    /**
     * The toolbar tool [name] names, if it is one that opens a panel. The
     * others act on the field (paste, select all, undo) or on the phone
     * (flashlight), which is more than "open a tool" promises, so they are
     * not reachable here at all.
     */
    fun panelTool(name: String): ToolbarTool? {
        val tool = ToolbarTool.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            ?: return null
        return tool.takeIf { t -> PanelMode.entries.any { it.name == t.name } }
    }

    /**
     * In a field's privateImeOptions, the app's own tool shortcut screen asking
     * for a tool's panel (#970): `com.wasimaster.wmkeyboard.openTool=LEVEL`.
     * The keyboard reads it only from fields of this package.
     */
    private const val PRIVATE_OPTION_OPEN_TOOL = "com.wasimaster.wmkeyboard.openTool="

    /** The privateImeOptions a field sets to open [tool]'s panel; see [toolFromPrivateOptions]. */
    fun privateOptionsFor(tool: ToolbarTool): String = PRIVATE_OPTION_OPEN_TOOL + tool.name

    /** The panel tool a field's privateImeOptions ask for, or null. */
    fun toolFromPrivateOptions(options: String?): ToolbarTool? = options
        ?.split(',')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith(PRIVATE_OPTION_OPEN_TOOL) }
        ?.removePrefix(PRIVATE_OPTION_OPEN_TOOL)
        ?.let(::panelTool)

    /**
     * An on/off extra, read leniently: a real boolean from `am broadcast --ez`,
     * or the string an automation app sends (`true`, `on`, `1`, and their
     * opposites). Null when absent or unreadable, which the toggles read as
     * "flip it".
     */
    fun parseSwitch(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase()) {
            "true", "on", "1", "yes" -> true
            "false", "off", "0", "no" -> false
            else -> null
        }
        else -> null
    }

    /**
     * [EXTRA_HOURS] for a pause: 0 when it is missing or 0, which is until
     * resumed, the hours when they are a whole number from 1 to
     * [BackupPause.MAX_HOURS], and null for anything else, which is refused
     * rather than read as forever.
     */
    fun pauseHours(value: Any?): Int? {
        val hours = when (value) {
            null -> return 0
            is Number -> value.toDouble().takeIf { it == Math.floor(it) }?.toInt()
            is String -> value.trim().let { if (it.isEmpty()) 0 else it.toIntOrNull() }
            else -> null
        } ?: return null
        return hours.takeIf { it in 0..BackupPause.MAX_HOURS }
    }

    /** A word a dictionary can hold: one token, not a sentence. */
    fun cleanWord(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_WORD_LENGTH && it.none(Char::isWhitespace) }

    private const val MAX_WORD_LENGTH = 64

    /** The broadcast [EVENT_LAYOUT_CHANGED] sends for [spec]. */
    fun layoutChangedIntent(spec: LayoutSpec): Intent =
        Intent(EVENT_LAYOUT_CHANGED)
            .putExtra(EXTRA_LAYOUT, spec.id)
            .putExtra(EXTRA_LANGUAGE, spec.language().id)
            .putExtra(EXTRA_NAME, spec.name)
}

/**
 * Where the automation intents land. Exported, deliberately: `adb` and
 * automation apps are the whole audience, and the Automation settings are the
 * gate. See [KeyboardAutomation].
 *
 * The answer goes back as the broadcast's result: `RESULT_OK` with what was
 * done, or `RESULT_CANCELED` with the reason. `adb shell am broadcast` prints
 * it; a plain fire-and-forget broadcast from an automation app ignores it.
 */
class KeyboardAutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.takeIf { it in KeyboardAutomation.actions } ?: return
        // Only an ordered broadcast has a result to set; setting one on any
        // other logs an error for nothing.
        val ordered = isOrderedBroadcast
        // Gone once goAsync has taken the pending result, and only there when
        // the sender chose to give its name.
        val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) sentFromPackage.orEmpty() else ""
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Main.immediate).launch {
            val (code, data) = try {
                withTimeout(TIMEOUT_MS) { handle(app, action, intent, sender) }
            } catch (e: Exception) {
                fail("failed: ${e.message ?: e.javaClass.simpleName}")
            }
            DebugLog.i("automation", "$action → $code $data")
            if (ordered) pending.setResult(code, data, null)
            pending.finish()
        }
    }

    private suspend fun handle(context: Context, action: String, intent: Intent, sender: String): Pair<Int, String> {
        val repository = SettingsRepository(context)
        val automation = repository.automation.first()
        if (!automation.enabled) {
            return fail("other apps may not control the keyboard; turn on Settings › Privacy › Let other apps control the keyboard")
        }
        val host = KeyboardAutomation.host
        val settings = host?.settings ?: repository.settings.first()
        val needs = KeyboardAutomation.permissions[action]
            ?: KeyboardAutomation.incognitoPermission(
                KeyboardAutomation.parseSwitch(extra(intent, KeyboardAutomation.EXTRA_ENABLED)) ?: !settings.incognito,
            )
        if (!needs.offered) return fail("\"${needs.key}\" is not part of this edition of WM Keyboard")
        if (!automation.allows(needs)) {
            return fail("not allowed: turn on \"${needs.key}\" under Settings › Privacy › Allowed actions")
        }
        return when (action) {
            KeyboardAutomation.ACTION_LIST_LAYOUTS -> ok(
                KeyboardAutomation.describe(
                    settings.enabledLayoutIds,
                    host?.layoutId ?: settings.activeLayoutId,
                    settings.customLayouts,
                ),
            )
            KeyboardAutomation.ACTION_SET_LAYOUT,
            KeyboardAutomation.ACTION_SET_LANGUAGE,
            KeyboardAutomation.ACTION_NEXT_LAYOUT,
            KeyboardAutomation.ACTION_PREVIOUS_LAYOUT,
            -> switchLayout(repository, host, settings, action, intent)
            KeyboardAutomation.ACTION_SET_MODE -> {
                if (!settings.modesEnabled) return fail("keyboard modes are turned off")
                val wanted = intent.getStringExtra(KeyboardAutomation.EXTRA_MODE)
                    ?: return fail("missing extra \"${KeyboardAutomation.EXTRA_MODE}\"")
                val id = KeyboardAutomation.matchMode(wanted, settings.keyboardModes)
                    ?: return fail("no mode is called \"$wanted\"")
                val running = host ?: return fail(NOT_RUNNING)
                running.selectMode(id.takeUnless { it == KeyboardAutomation.MODE_AUTO })
                ok(id)
            }
            KeyboardAutomation.ACTION_SET_THEME -> {
                val wanted = intent.getStringExtra(KeyboardAutomation.EXTRA_THEME)
                    ?: return fail("missing extra \"${KeyboardAutomation.EXTRA_THEME}\"")
                val id = KeyboardAutomation.matchTheme(wanted, settings.customThemes)
                    ?: return fail("no theme is called \"$wanted\"")
                repository.setKeyboardThemeId(id)
                // The pick is stored either way, but the automatic pair wins
                // over it on screen; say so rather than look broken.
                ok(if (settings.autoTheme.enabled) "$id (automatic theme is on and takes precedence)" else id)
            }
            KeyboardAutomation.ACTION_SHOW_KEYBOARD -> {
                val running = host ?: return fail(NOT_RUNNING)
                running.showKeyboard()
                ok("shown")
            }
            KeyboardAutomation.ACTION_SET_PINNED -> {
                val on = switchTo(intent, settings.persistentKeyboard)
                if (host != null) host.setPinned(on) else repository.setPersistentKeyboard(on)
                ok(onOff(on))
            }
            KeyboardAutomation.ACTION_SET_FLOATING_BUTTON -> {
                // Telegram group: the floating button from another app. The
                // overlay grant is still the user's to give; without it the
                // switch is set and the button waits for it.
                val on = switchTo(intent, settings.summonButton.enabled)
                repository.setSummonButton(on)
                ok(onOff(on))
            }
            KeyboardAutomation.ACTION_SET_KEY_SOUND -> {
                val on = switchTo(intent, settings.sound.enabled)
                repository.setKeySound(on)
                ok(onOff(on))
            }
            KeyboardAutomation.ACTION_SET_HAPTICS -> {
                val on = switchTo(intent, settings.haptics.enabled)
                repository.setHapticFeedback(on)
                ok(onOff(on))
            }
            KeyboardAutomation.ACTION_SET_POWER_SAVING -> {
                val on = switchTo(intent, settings.powerSaving.manual)
                repository.setPowerSavingManual(on)
                ok(onOff(on))
            }
            KeyboardAutomation.ACTION_SET_DATA_SAVER -> {
                val on = switchTo(intent, settings.dataSaver.manual)
                repository.setDataSaverManual(on)
                ok(onOff(on))
            }
            KeyboardAutomation.ACTION_SET_POSITION -> {
                val raw = intent.getStringExtra(KeyboardAutomation.EXTRA_POSITION)
                val position = KeyboardAutomation.Position.parse(raw)
                    ?: return fail("\"${KeyboardAutomation.EXTRA_POSITION}\" must be normal, left, right, split or floating")
                repository.setOneHandedMode(position.oneHanded)
                repository.setSplitKeyboard(position.split)
                // Floating is remembered per orientation (#584), so this sets
                // the one the device is held in, as the toolbar tool does.
                val landscape = context.resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE
                repository.setFloatingKeyboard(landscape, position.floating)
                ok(position.name.lowercase())
            }
            KeyboardAutomation.ACTION_OPEN_TOOL -> {
                val wanted = intent.getStringExtra(KeyboardAutomation.EXTRA_TOOL)
                    ?: return fail("missing extra \"${KeyboardAutomation.EXTRA_TOOL}\"")
                val tool = KeyboardAutomation.panelTool(wanted)
                    ?: return fail("\"$wanted\" is not a tool with a panel")
                val running = host ?: return fail(NOT_RUNNING)
                running.openTool(tool)?.let { return fail(it) }
                ok(tool.name)
            }
            KeyboardAutomation.ACTION_SET_INCOGNITO -> {
                val on = switchTo(intent, settings.incognito)
                repository.setIncognito(on)
                ok(onOff(on))
            }
            KeyboardAutomation.ACTION_ADD_WORD -> {
                val word = KeyboardAutomation.cleanWord(intent.getStringExtra(KeyboardAutomation.EXTRA_WORD))
                    ?: return fail("\"${KeyboardAutomation.EXTRA_WORD}\" must be one word")
                val running = host ?: return fail(NOT_RUNNING)
                running.addWord(word)
                ok(word)
            }
            KeyboardAutomation.ACTION_BLOCK_WORD -> {
                val word = KeyboardAutomation.cleanWord(intent.getStringExtra(KeyboardAutomation.EXTRA_WORD))
                    ?: return fail("\"${KeyboardAutomation.EXTRA_WORD}\" must be one word")
                val language = intent.getStringExtra(KeyboardAutomation.EXTRA_LANGUAGE)?.trim()?.takeIf { it.isNotEmpty() }
                repository.addSuggestionBlacklistWord(word, language)
                ok(word.lowercase())
            }
            KeyboardAutomation.ACTION_BACKUP_NOW -> {
                if (!AutoBackupScheduler.runNow(context, settings.autoBackup)) {
                    return fail("no location is ticked for automatic backup")
                }
                ok("backup started")
            }
            KeyboardAutomation.ACTION_PAUSE_BACKUP, KeyboardAutomation.ACTION_PAUSE_SYNC -> {
                val hours = KeyboardAutomation.pauseHours(extra(intent, KeyboardAutomation.EXTRA_HOURS))
                    ?: return fail(
                        "\"${KeyboardAutomation.EXTRA_HOURS}\" must be a whole number of hours from 1 to " +
                            "${BackupPause.MAX_HOURS}, or left out to pause until resumed",
                    )
                val until = BackupPause.until(System.currentTimeMillis(), hours.takeIf { it > 0 })
                if (action == KeyboardAutomation.ACTION_PAUSE_BACKUP) {
                    repository.setAutoBackupPausedUntil(until)
                } else {
                    repository.setSyncPausedUntil(until)
                }
                ok(if (hours > 0) "paused for $hours h" else "paused until resumed")
            }
            KeyboardAutomation.ACTION_RESUME_BACKUP -> {
                repository.setAutoBackupPausedUntil(0L)
                ok("resumed")
            }
            KeyboardAutomation.ACTION_RESUME_SYNC -> {
                repository.setSyncPausedUntil(0L)
                // What the other devices changed during the pause, now.
                SyncScheduler.requestSoon(context, repository.settings.first().autoBackup, delayMs = 0L)
                ok("resumed")
            }
            KeyboardAutomation.ACTION_EXPORT_DATA -> {
                val sections = AutomationExport.parseSections(intent.getStringExtra(KeyboardAutomation.EXTRA_SECTIONS))
                    ?: return fail(
                        "\"${KeyboardAutomation.EXTRA_SECTIONS}\" takes " +
                            AutomationExport.EXPORTABLE.joinToString(", ") { it.id },
                    )
                if (automation.exportFolder.isEmpty()) {
                    return fail("no export folder: pick one under Settings › Privacy › Allowed actions")
                }
                if (!AutoBackupScheduler.runExport(context, sections, sender)) return fail("could not start the export")
                ok("export started: ${AutomationExport.FILE_NAME}")
            }
            KeyboardAutomation.ACTION_TYPE_TEXT -> {
                val running = host ?: return fail(NOT_RUNNING)
                val text = intent.getStringExtra(KeyboardAutomation.EXTRA_TEXT)
                val snippet = intent.getStringExtra(KeyboardAutomation.EXTRA_SNIPPET)
                val refused = when {
                    !text.isNullOrEmpty() -> {
                        if (text.length > KeyboardAutomation.MAX_TEXT_LENGTH) {
                            return fail("\"${KeyboardAutomation.EXTRA_TEXT}\" is longer than ${KeyboardAutomation.MAX_TEXT_LENGTH} characters")
                        }
                        running.typeText(text)
                    }
                    !snippet.isNullOrBlank() -> running.typeSnippet(snippet.trim())
                    else -> return fail("missing extra \"${KeyboardAutomation.EXTRA_TEXT}\" or \"${KeyboardAutomation.EXTRA_SNIPPET}\"")
                }
                refused?.let { return fail(it) }
                ok("typed")
            }
            else -> fail("unknown action $action")
        }
    }

    private suspend fun switchLayout(
        repository: SettingsRepository,
        host: KeyboardAutomation.Host?,
        settings: KeyboardSettings,
        action: String,
        intent: Intent,
    ): Pair<Int, String> {
        val current = host?.layoutId ?: settings.activeLayoutId
        val outcome = KeyboardAutomation.resolve(
            action = action,
            layout = intent.getStringExtra(KeyboardAutomation.EXTRA_LAYOUT),
            language = intent.getStringExtra(KeyboardAutomation.EXTRA_LANGUAGE),
            enabled = settings.enabledLayoutIds,
            current = current,
            recent = settings.recentLayoutIds,
            customs = settings.customLayouts,
        )
        return when (outcome) {
            is KeyboardAutomation.Outcome.Switch -> {
                if (host != null) {
                    host.selectLayout(outcome.layoutId)
                } else {
                    repository.setActiveLayoutId(outcome.layoutId, recentFrom = current)
                }
                ok(outcome.layoutId)
            }
            is KeyboardAutomation.Outcome.Unchanged -> ok(outcome.layoutId)
            is KeyboardAutomation.Outcome.Failed -> fail(outcome.reason)
        }
    }

    /** [EXTRA_ENABLED] as asked, or the opposite of [current] when it is missing. */
    private fun switchTo(intent: Intent, current: Boolean): Boolean =
        KeyboardAutomation.parseSwitch(extra(intent, KeyboardAutomation.EXTRA_ENABLED)) ?: !current

    @Suppress("DEPRECATION")
    private fun extra(intent: Intent, name: String): Any? = intent.extras?.get(name)

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    private fun ok(data: String) = Activity.RESULT_OK to data

    private fun fail(reason: String) = Activity.RESULT_CANCELED to reason

    private companion object {
        /** Well inside the ten seconds a receiver gets before Android calls it hung. */
        const val TIMEOUT_MS = 8_000L

        const val NOT_RUNNING = "the keyboard is not running; select WM Keyboard as the keyboard first"
    }
}
