package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.config.BuildConfig

/**
 * One thing another app may ask the keyboard to do through its automation
 * intents (adb, Tasker, MacroDroid, Automate). Each is one row on the
 * Automation screen, and the receiver refuses an intent whose permission is
 * not allowed.
 *
 * [key] is the stored name and must never change: renaming it quietly resets
 * the user's choice. [defaultAllowed] splits the list in two. The first group
 * can do no more than a tap on the keyboard's own controls, reversibly, and is
 * on once the master switch is. The second changes what the keyboard knows,
 * writes outside it, tells other apps something, or types, so each one stays
 * off until asked for by name.
 */
enum class AutomationPermission(val key: String, val defaultAllowed: Boolean) {
    /** Set, cycle and list the enabled layouts and languages. */
    LAYOUT("layout", true),

    /** Apply a keyboard mode by hand, or go back to automatic. */
    MODE("mode", true),

    /** Pick the keyboard theme. */
    THEME("theme", true),

    /** Bring the keyboard up, and pin or unpin it. */
    SHOW_PIN("show_pin", true),

    /** Key sound and haptics on or off. */
    FEEDBACK("feedback", true),

    /** Power saving and data saver on or off. */
    SAVERS("savers", true),

    /** Normal, one-handed, split or floating. */
    POSITION("position", true),

    /** Open a tool's panel on the keyboard. */
    OPEN_TOOL("open_tool", true),

    /** Turn incognito on. Only ever makes the keyboard remember less. */
    INCOGNITO_ON("incognito_on", true),

    /** Turn incognito off, so learning resumes. */
    INCOGNITO_OFF("incognito_off", false),

    /** Add a word to the personal dictionary, or block a word from suggestions. */
    WORDS("words", false),

    /** Start a backup to the automatic backup's destination. */
    BACKUP("backup", false),

    /** Pause and resume the scheduled backup and sync. Off: a pause is a gap in the user's backups. */
    BACKUP_PAUSE("backup_pause", false),

    /** Write the user's data to a file in the folder they picked; see [AutomationExport]. */
    EXPORT_DATA("export_data", false),

    /** Announce every layout switch to any app that listens. */
    LAYOUT_EVENTS("layout_events", false),

    /** Type text, or a snippet, into the focused field. */
    TYPE_TEXT("type_text", false),
    ;

    /**
     * Whether this build offers the permission at all. The Google Play build
     * leaves [TYPE_TEXT] out: the receiver is exported with no permission, so
     * once it is allowed any installed app can type into whatever field has
     * the keyboard, and on Play that is an input-injection surface in an app
     * reviewed as a keyboard. Refused even when a backup from another build
     * restores it as allowed.
     */
    val offered: Boolean get() = !(this == TYPE_TEXT && BuildConfig.ENABLE_PLAY_STORE)

    companion object {
        /** The permissions this build offers, in screen order. */
        val offeredEntries: List<AutomationPermission> get() = entries.filter { it.offered }

        /** The stored name's permission, or null for one this version does not know. */
        fun byKey(key: String): AutomationPermission? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Whether other apps may control the keyboard, and what they may do.
 *
 * Off by default, which turns every automation intent away, the layout ones
 * included. Kept out of [KeyboardSettings] (see the JVM ceiling note there) and
 * read as its own flow, [SettingsRepository.automation]; the keys stay in the
 * same DataStore, so a settings backup carries them.
 */
data class AutomationSettings(
    /** The master switch: "Let other apps control the keyboard". */
    val enabled: Boolean = false,
    /** What is allowed while [enabled]. Starts as every [AutomationPermission.defaultAllowed]. */
    val allowed: Set<AutomationPermission> = DEFAULT_ALLOWED,
    /** The tree [AutomationExport] writes into, or empty until the user picks one. */
    val exportFolder: String = "",
    /** The exports so far, newest first, at most [AutomationExport.MAX_LOG]. */
    val exportLog: List<AutomationExportRecord> = emptyList(),
) {
    /** Whether an intent needing [permission] goes through. */
    fun allows(permission: AutomationPermission): Boolean = enabled && permission.offered && permission in allowed

    companion object {
        val DEFAULT_ALLOWED: Set<AutomationPermission> =
            AutomationPermission.entries.filterTo(LinkedHashSet()) { it.defaultAllowed }
    }
}
