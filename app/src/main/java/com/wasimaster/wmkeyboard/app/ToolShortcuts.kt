package com.wasimaster.wmkeyboard.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.clock.ClockEngine
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.ime.KeyboardAutomation

/*
 * The three ways a tool opens without a text field to type in: an icon on the
 * home screen, an entry of its own in the app list, and a line in the menu a
 * press and hold on the WM Keyboard icon opens. Each is the user's to choose,
 * tool by tool, on the tool's page and on Tools › Shortcuts to tools.
 *
 * All three open the same thing ([ToolLaunch.intent]): the clock, the notes and
 * the to-do list open their screens in this app; a tool the keyboard can run
 * full screen opens full screen ([ToolShortcutActivity]); any other tool with
 * a panel opens over a text box of its own, with the keyboard up and the
 * tool's panel open.
 */

/** What opens a tool from outside the keyboard, and which tools can be opened so. */
internal object ToolLaunch {
    /** Tools with a screen of their own in this app, which every way in lands on. */
    val appScreens: Map<ToolbarTool, String> = mapOf(
        ToolbarTool.CLOCK to ClockEngine.ROUTE,
        ToolbarTool.NOTES to NOTES_ROUTE,
        ToolbarTool.TODO to TODO_ROUTE,
    )

    /** Whether [tool] can be opened from the home screen, the app list or the icon's menu: it has a panel. */
    fun launchable(tool: ToolbarTool): Boolean = KeyboardAutomation.panelTool(tool.name) != null && isSupportedTool(tool)

    /** The tools that can, in the order the Tools screen lists them. */
    val tools: List<ToolbarTool> by lazy {
        ToolGroups.flatMap { it.second }.distinct().filter(::launchable)
    }

    /** What an icon for [tool] starts: its screen in this app, or [ToolShortcutActivity]. */
    fun intent(context: Context, tool: ToolbarTool): Intent =
        appScreens[tool]?.let { pageIntent(context, it) } ?: ToolShortcutActivity.intent(context, tool)

    /** A settings page by its route, the address the long-press entries and links carry. */
    fun pageIntent(context: Context, route: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("wmkeyboard://settings/$route"))
            .setClassName(context, MainActivity::class.java.name)

    /** The app-list icon drawn for [tool], also its icon in the long-press menu. */
    fun icon(context: Context, tool: ToolbarTool): IconCompat =
        ToolAppIcons[tool]?.let { IconCompat.createWithResource(context, it) }
            ?: IconCompat.createWithResource(context, R.drawable.ic_shortcut_tools)
}

/**
 * A tool's own entry in the app list: one `<activity-alias>` of
 * [ToolShortcutActivity] per tool, named [ALIAS_PREFIX] plus the tool's name,
 * disabled in the manifest and switched on here. As with [LauncherName], the
 * component's state is the setting: Android keeps it across updates and
 * reboots, so no preference stands behind it to disagree.
 */
internal object ToolAppList {
    /** Stored state: an alias renamed in the manifest drops every tool the user had in the list. */
    const val ALIAS_PREFIX = "com.wasimaster.wmkeyboard.tool."

    private fun component(context: Context, tool: ToolbarTool) = ComponentName(context.packageName, ALIAS_PREFIX + tool.name)

    /** The tool an alias stands for, from the class name a launcher started. */
    fun toolOf(className: String?): ToolbarTool? =
        className?.takeIf { it.startsWith(ALIAS_PREFIX) }?.removePrefix(ALIAS_PREFIX)?.let(KeyboardAutomation::panelTool)

    fun isShown(context: Context, tool: ToolbarTool): Boolean = runCatching {
        context.packageManager.getComponentEnabledSetting(component(context, tool)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }.getOrDefault(false)

    /** The tools with an entry, read once for a screen. */
    fun shown(context: Context): Set<ToolbarTool> = ToolLaunch.tools.filterTo(HashSet()) { isShown(context, it) }

    /**
     * Puts [tool] in the app list or takes it out. Taking it out returns it to
     * the manifest's default, which is out. A task opened from the entry goes
     * with it, which Android does for any disabled component; this is only
     * ever switched from the settings app, which is a task of its own.
     */
    fun setShown(context: Context, tool: ToolbarTool, shown: Boolean) {
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                component(context, tool),
                if (shown) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}

/**
 * The menu a press and hold on the app icon opens: settings pages and tools,
 * in the user's order, published as dynamic shortcuts. A launcher shows the
 * first four or five; Android takes more but none draws them.
 *
 * The four pages were static shortcuts in the manifest until the menu became
 * the user's, and nothing at run time can take one of those away or reorder
 * it. Their ids are not reused:
 * Android keeps a home-screen copy of a removed static shortcut disabled for
 * good, and refuses to let the app touch an id it once declared.
 */
internal object AppIconMenu {
    /** A settings page the menu can hold. */
    enum class Page(
        val route: String,
        @StringRes val shortLabel: Int,
        @StringRes val longLabel: Int,
        @DrawableRes val icon: Int,
    ) {
        THEMES("themes", R.string.shortcut_themes_short, R.string.shortcut_themes_long, R.drawable.ic_shortcut_themes),
        AI_CHAT("ai_chat", R.string.shortcut_ai_chat_short, R.string.shortcut_ai_chat_long, R.drawable.ic_shortcut_ai_chat),
        LANGUAGES("languages", R.string.shortcut_languages_short, R.string.shortcut_languages_long, R.drawable.ic_shortcut_languages),
        TOOLS("tools", R.string.shortcut_tools_short, R.string.shortcut_tools_long, R.drawable.ic_shortcut_tools),
    }

    /** One line of the menu. */
    sealed interface Entry {
        val token: String

        data class PageEntry(val page: Page) : Entry {
            override val token: String get() = PAGE + page.name
        }

        data class ToolEntry(val tool: ToolbarTool) : Entry {
            override val token: String get() = TOOL + tool.name
        }

        companion object {
            fun of(token: String): Entry? = when {
                token.startsWith(PAGE) -> Page.entries.firstOrNull { it.name == token.removePrefix(PAGE) }?.let(::PageEntry)
                token.startsWith(TOOL) -> ToolbarTool.entries.firstOrNull { it.name == token.removePrefix(TOOL) }
                    ?.takeIf(ToolLaunch::launchable)?.let(::ToolEntry)
                else -> null
            }
        }
    }

    private const val PAGE = "page:"
    private const val TOOL = "tool:"

    /** What a fresh install's menu holds: the four pages the manifest used to, nearest the icon first. */
    val Default: List<Entry> = listOf(Page.THEMES, Page.AI_CHAT, Page.LANGUAGES, Page.TOOLS).map(Entry::PageEntry)

    private const val PREFS = "app_icon_menu"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_PUBLISHED = "published"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The menu as the user set it, nearest the icon first. */
    fun entries(context: Context): List<Entry> {
        val stored = prefs(context).getString(KEY_ENTRIES, null) ?: return Default
        return stored.split(',').filter { it.isNotBlank() }.mapNotNull(Entry::of).distinct()
    }

    /** Keeps [entries] as the menu and hands it to the launcher. */
    fun set(context: Context, entries: List<Entry>) {
        prefs(context).edit { putString(KEY_ENTRIES, entries.distinct().joinToString(",") { it.token }) }
        publish(context)
    }

    /** Back to [Default]. */
    fun reset(context: Context) {
        prefs(context).edit { remove(KEY_ENTRIES) }
        publish(context)
    }

    fun contains(context: Context, tool: ToolbarTool): Boolean = Entry.ToolEntry(tool) in entries(context)

    /** Adds [tool] at the end of the menu, or takes it out. */
    fun setTool(context: Context, tool: ToolbarTool, shown: Boolean) {
        val now = entries(context)
        val entry = Entry.ToolEntry(tool)
        set(context, if (shown) (now - entry) + entry else now - entry)
    }

    /** How many lines Android takes for one icon; a launcher draws four or five of them. */
    fun capacity(context: Context): Int =
        runCatching { ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) }.getOrDefault(4).coerceAtLeast(1)

    /** The icon the menu hangs off: whichever launcher name is on, or none while the icon is hidden. */
    private fun launcherActivity(context: Context): ComponentName? = when {
        !LauncherName.isShown(context) -> null
        LauncherName.isShort(context) -> ComponentName(context, LauncherName.SHORT_ALIAS)
        else -> ComponentName(context, LauncherName.FULL_ALIAS)
    }

    /**
     * Hands the menu to the launcher, when what it last got differs: another
     * order, another language, another launcher name or another build. The
     * process starts every time the keyboard does, and Android rate-limits
     * an app in the background, so an unchanged menu costs no call at all.
     *
     * Each line names the icon it belongs to. Left to Android it goes on the
     * first app-list entry the manifest has, which with a tool's own entry
     * switched on can be that tool's.
     */
    fun publish(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val entries = entries(context)
        val activity = launcherActivity(context)
        val signature = listOf(
            entries.joinToString(",") { it.token },
            activity?.className.orEmpty(),
            context.resources.configuration.locales.toLanguageTags(),
            BuildConfig.VERSION_CODE.toString(),
        ).joinToString("|")
        val prefs = prefs(context)
        if (prefs.getString(KEY_PUBLISHED, null) == signature) return
        val done = runCatching {
            if (activity == null) {
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                true
            } else {
                val shortcuts = entries.take(capacity(context)).mapIndexed { rank, entry ->
                    shortcut(context, entry, rank, activity)
                }
                ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            }
        }.getOrDefault(false)
        if (done) prefs.edit { putString(KEY_PUBLISHED, signature) }
    }

    /**
     * [publish] for a process that just started, on a thread of its own: the
     * first start after the update that took the manifest's four lines away
     * is what puts them back, before anyone opens the settings. Before the
     * first unlock neither the stored menu nor the shortcut service can be
     * read, so a process the phone started at boot waits for the unlock.
     */
    fun publishOnStart(context: Context) {
        val app = context.applicationContext ?: context
        val run = { Thread({ runCatching { publish(app) } }, "app-icon-menu").start() }
        if (DirectBoot.isUserUnlocked(app)) {
            run()
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_UNLOCKED) return
                runCatching { app.unregisterReceiver(this) }
                run()
            }
        }
        runCatching {
            ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    private fun shortcut(context: Context, entry: Entry, rank: Int, activity: ComponentName): ShortcutInfoCompat =
        when (entry) {
            is Entry.PageEntry -> ShortcutInfoCompat.Builder(context, "menu_page_" + entry.page.route)
                .setShortLabel(context.getString(entry.page.shortLabel))
                .setLongLabel(context.getString(entry.page.longLabel))
                .setIcon(IconCompat.createWithResource(context, entry.page.icon))
                .setIntent(ToolLaunch.pageIntent(context, entry.page.route))
            is Entry.ToolEntry -> ShortcutInfoCompat.Builder(context, "menu_tool_" + entry.tool.name)
                .setShortLabel(context.getString(toolTitle(entry.tool)))
                .setIcon(ToolLaunch.icon(context, entry.tool))
                .setIntent(ToolLaunch.intent(context, entry.tool))
        }.setRank(rank).setActivity(activity).build()

    /** A line's name, for the settings screen. */
    @StringRes
    fun label(entry: Entry): Int = when (entry) {
        is Entry.PageEntry -> entry.page.longLabel
        is Entry.ToolEntry -> toolTitle(entry.tool)
    }
}
