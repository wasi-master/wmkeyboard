package com.wasimaster.wmkeyboard.app.lock

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R

/** Whether a lockable thing is a whole destination or one irreversible button. */
internal enum class LockKind { SCREEN, ACTION }

/** The heading a target sits under in the configurator. */
internal enum class LockGroup(@StringRes val title: Int) {
    /** Screens that show what the keyboard has learned or been told. */
    CONTENT(R.string.privacy_lock_group_content),

    /** Buttons that throw something away. */
    DESTRUCTIVE(R.string.privacy_lock_group_destructive),

    /** Anything that writes the user's data out of the app. */
    EXPORT(R.string.privacy_lock_group_export),
}

/**
 * One thing the user can put behind a fingerprint.
 *
 * [label] is the **existing** title resource of the screen or row being
 * protected wherever one exists, rather than a new string written for the
 * configurator. Three things fall out of that: the configurator reads in the
 * same words as the thing it guards, those words cannot drift apart later, and
 * the row inherits the right glyph from `SettingsRouteIcons` /
 * `SettingsRowIcons` without a second table to keep in step.
 *
 * Three targets are not rows and so have no title to borrow: the storage
 * screen's delete buttons and its settings wipe are dialogs, and the export
 * button is a bare `Button` with a verb on it. Those carry their own
 * `privacy_lock_*` label, written to name the *thing being locked* rather than
 * the press ("Delete every setting", not "Delete every setting?").
 */
internal data class LockTarget(
    /**
     * Stable id, stored in DataStore. Never translated, never renamed —
     * renaming one silently unticks whatever the user had chosen.
     */
    val id: String,
    @StringRes val label: Int,
    val kind: LockKind,
    val group: LockGroup,
    /** The NavHost route. Non-null exactly when [kind] is [LockKind.SCREEN]. */
    val route: String? = null,
    /**
     * Whether switching the feature on for the first time ticks this. The
     * seed only ever runs once, on an empty selection — see the master toggle
     * in `AppLockSettingsScreen`.
     */
    val onByDefault: Boolean = false,
)

/**
 * Everything the fingerprint lock can be pointed at.
 *
 * A curated list, not every route in the app. Sixty checkboxes is a list
 * nobody reads, and most settings screens hold nothing private: what colour
 * the keyboard is, which layouts are enabled, how long a long-press takes.
 * What is here is the content the keyboard has gathered about its user, the
 * buttons that destroy it, and the paths that copy it out of the app.
 *
 * Adding one later is cheap on purpose. A screen costs an entry in this file
 * and nothing else — the gate in `SettingsScreen` looks routes up here, and
 * the configurator renders whatever [all] contains. An action costs that entry
 * plus a `lock =` argument at its call site. Neither needs a new string, a new
 * icon, a route colour or a search-index row; `AppLockTargetsTest` fails if
 * either half is missing.
 */
internal object AppLockTargets {

    /**
     * The configurator itself.
     *
     * Locked whenever the feature is on, no matter what the user ticked: a
     * switch that can be flipped off without proving who you are is not a
     * lock. Kept out of [all] so it never draws as a checkbox — nobody gets to
     * untick the lock on the lock.
     */
    val SELF = LockTarget(
        id = "applock_config",
        label = R.string.privacy_lock_title,
        kind = LockKind.SCREEN,
        group = LockGroup.CONTENT,
        route = ROUTE,
    )

    val all: List<LockTarget> = listOf(
        // ---- screens ----
        LockTarget(
            "screen_dictionary", R.string.home_screen_dictionary_title,
            LockKind.SCREEN, LockGroup.CONTENT, route = "dictionary", onByDefault = true,
        ),
        LockTarget(
            "screen_ai_history", R.string.home_screen_ai_history_title,
            LockKind.SCREEN, LockGroup.CONTENT, route = "ai_history", onByDefault = true,
        ),
        LockTarget(
            "screen_ai_chat", R.string.home_screen_ai_chat_title,
            LockKind.SCREEN, LockGroup.CONTENT, route = "ai_chat", onByDefault = true,
        ),
        LockTarget(
            "screen_privacy", R.string.home_privacy_title,
            LockKind.SCREEN, LockGroup.CONTENT, route = "privacy",
        ),
        LockTarget(
            "screen_statistics", R.string.statistics_title,
            LockKind.SCREEN, LockGroup.CONTENT, route = "statistics",
        ),
        LockTarget(
            "screen_blacklist", R.string.home_screen_blacklist_title,
            LockKind.SCREEN, LockGroup.CONTENT, route = "blacklist",
        ),
        LockTarget(
            "screen_debug_log", R.string.home_screen_debug_log_title,
            LockKind.SCREEN, LockGroup.CONTENT, route = "debug_log",
        ),
        LockTarget(
            "screen_backup", R.string.home_backup_title,
            LockKind.SCREEN, LockGroup.EXPORT, route = "backup", onByDefault = true,
        ),
        LockTarget(
            "screen_storage", R.string.about_storage_title,
            LockKind.SCREEN, LockGroup.DESTRUCTIVE, route = "storage",
        ),

        // ---- actions ----
        LockTarget(
            "action_delete_learned_words", R.string.privacy_delete_learned_words_title,
            LockKind.ACTION, LockGroup.DESTRUCTIVE, onByDefault = true,
        ),
        LockTarget(
            "action_delete_ai_chats", R.string.toolai_delete_chats_title,
            LockKind.ACTION, LockGroup.DESTRUCTIVE, onByDefault = true,
        ),
        LockTarget(
            "action_storage_delete", R.string.privacy_lock_storage_delete_title,
            LockKind.ACTION, LockGroup.DESTRUCTIVE, onByDefault = true,
        ),
        LockTarget(
            "action_factory_reset", R.string.privacy_lock_factory_reset_title,
            LockKind.ACTION, LockGroup.DESTRUCTIVE, onByDefault = true,
        ),
        LockTarget(
            "action_export_settings", R.string.privacy_lock_export_title,
            LockKind.ACTION, LockGroup.EXPORT, onByDefault = true,
        ),
        LockTarget(
            "action_clear_emoji_history", R.string.langemoji_emoji_clear_history_title,
            LockKind.ACTION, LockGroup.DESTRUCTIVE,
        ),
        LockTarget(
            "action_clear_blacklist", R.string.backup_blacklist_clear_title,
            LockKind.ACTION, LockGroup.DESTRUCTIVE,
        ),
        LockTarget(
            "action_forget_app_languages", R.string.langemoji_lang_forget_apps_title,
            LockKind.ACTION, LockGroup.DESTRUCTIVE,
        ),
    )

    private val byId: Map<String, LockTarget> = all.associateBy { it.id }

    // Keyed off the nullable route directly rather than filtering by kind and
    // then asserting: a screen is exactly a target with a route, and
    // AppLockTargetsTest holds those two facts together.
    private val byRoute: Map<String, LockTarget> =
        all.mapNotNull { target -> target.route?.let { it to target } }.toMap()

    /** The targets the first enable ticks. */
    val defaultIds: Set<String> = all.filter { it.onByDefault }.map { it.id }.toSet()

    /** In configurator order: the groups as declared, targets as declared. */
    val grouped: List<Pair<LockGroup, List<LockTarget>>> =
        LockGroup.entries.mapNotNull { group ->
            all.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { group to it }
        }

    /** The action with this id, for a call site that names one. */
    operator fun get(id: String): LockTarget? = byId[id]

    /**
     * The target guarding a destination, if any.
     *
     * A route under a locked screen inherits that screen's lock: `backup/auto`
     * is guarded by whatever guards `backup`. Without this, splitting a locked
     * screen into sub-pages would silently leave the sub-pages open — the
     * lookup is an exact map hit, and nothing else would notice. The prefix
     * walk runs only on a miss, so the common case (no lock at all, around
     * fifty of the app's sixty routes) stays one lookup and no allocation.
     */
    fun screen(route: String): LockTarget? {
        if (route == ROUTE) return SELF
        byRoute[route]?.let { return it }
        var end = route.lastIndexOf('/')
        while (end > 0) {
            val parent = route.substring(0, end)
            if (parent == ROUTE) return SELF
            byRoute[parent]?.let { return it }
            end = parent.lastIndexOf('/')
        }
        return null
    }

    /** The configurator's own route. */
    const val ROUTE = "applock"
}
