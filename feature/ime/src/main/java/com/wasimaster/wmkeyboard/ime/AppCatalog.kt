package com.wasimaster.wmkeyboard.ime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import com.wasimaster.wmkeyboard.core.settings.LauncherFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app-launcher tool's view of the package manager: the launchable apps,
 * and for one app at a time, the activities inside it.
 *
 * Visibility comes entirely from the manifest's MAIN/LAUNCHER `<queries>`
 * entry — the same one the suggestion engine's app-name index uses. That
 * entry makes every launcher-listed package fully visible, which is what lets
 * [loadActivities] enumerate a package's whole activity table without
 * QUERY_ALL_PACKAGES (which Play restricts and this app must never request).
 *
 * The user's other profiles, a work profile above all (#924), come through
 * [LauncherApps] instead, which any app may ask about the profiles of its own
 * user: no permission, and the same `<queries>` entry governs what it sees.
 * Android 15's private space is not among them; only the default home app
 * may list that.
 */
data class LauncherApp(
    val packageName: String,
    val label: String,
    /** Class name of the MAIN/LAUNCHER entry point. */
    val activityName: String,
    /**
     * The serial number of the profile the app lives in, or null for the
     * keyboard's own. A serial, not a [UserHandle], so the JVM tests can build
     * one; the service turns it back into a handle with [UserManager].
     */
    val profile: Long? = null,
    /** Its profile is paused (work apps turned off): drawn dimmed, and a tap asks to turn it on. */
    val paused: Boolean = false,
    /** [ApplicationInfo.category], or -1 when the app names none (or before Android 8). */
    val category: Int = -1,
) {
    /** The entry point, for the icon and the plain launch. Plain strings are
     * stored instead so the JVM unit tests can build catalog entries. */
    val component: ComponentName get() = ComponentName(packageName, activityName)

    /**
     * What the pinned, hidden and recent lists and the folders store: the
     * package for the keyboard's own profile, so every list saved before
     * #924 keeps working, and `package@serial` for another profile, where the
     * same package is a second, separate app.
     */
    val key: String get() = if (profile == null) packageName else "$packageName@$profile"

    /** From another profile, which gets the briefcase. */
    val otherProfile: Boolean get() = profile != null
}

/**
 * One cell of the browsing grid: a folder (#919) with the apps it shows, or
 * one app on its own.
 */
sealed interface LauncherCell {
    val key: String

    data class Folder(val folder: LauncherFolder, val apps: List<LauncherApp>) : LauncherCell {
        override val key: String get() = "folder:${folder.id}"
    }

    data class App(val app: LauncherApp) : LauncherCell {
        override val key: String get() = app.key
    }
}

data class LauncherActivity(
    val packageName: String,
    val className: String,
    /** The activity's own label when it has one, else its short class name. */
    val label: String,
    /**
     * Whether another app may start it. A non-exported activity throws
     * SecurityException on launch, so the panel dims these and only shows
     * them behind a setting.
     */
    val exported: Boolean,
    val enabled: Boolean,
    /** The profile serial of the app it belongs to; see [LauncherApp.profile]. */
    val profile: Long? = null,
    /**
     * The permission a caller must hold to start it, or null. An exported
     * screen behind one throws for an ordinary app but opens from the shell.
     */
    val permission: String? = null,
) {
    val component: ComponentName get() = ComponentName(packageName, className)
}

object AppCatalog {

    /**
     * All launchable apps, one entry per package, sorted by label. Empty on
     * a package-manager failure (a dying system service) — the panel shows
     * its empty state rather than crashing the keyboard.
     */
    suspend fun loadApps(pm: PackageManager): List<LauncherApp> = withContext(Dispatchers.IO) {
        runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { info ->
                    val activity = info.activityInfo ?: return@mapNotNull null
                    LauncherApp(
                        packageName = activity.packageName,
                        label = info.loadLabel(pm).toString(),
                        activityName = activity.name,
                        category = categoryOf(activity.applicationInfo),
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    /**
     * [loadApps], then, with [otherProfiles], the apps of every other profile
     * of this user (the work profile) after them, each tagged with its
     * profile. A profile that fails to list is left out rather than failing
     * the whole grid.
     */
    suspend fun loadAllApps(context: Context, otherProfiles: Boolean): List<LauncherApp> {
        val own = loadApps(context.packageManager)
        if (!otherProfiles) return own
        return own + withContext(Dispatchers.IO) { loadOtherProfileApps(context) }
    }

    private fun loadOtherProfileApps(context: Context): List<LauncherApp> {
        val users = context.getSystemService(UserManager::class.java) ?: return emptyList()
        val launcher = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val me = Process.myUserHandle()
        val profiles = runCatching { users.userProfiles }.getOrNull().orEmpty().filter { it != me }
        return profiles.flatMap { user ->
            runCatching {
                val serial = users.getSerialNumberForUser(user)
                val paused = isPaused(users, user)
                launcher.getActivityList(null, user)
                    .map { info ->
                        LauncherApp(
                            packageName = info.componentName.packageName,
                            label = info.label.toString(),
                            activityName = info.componentName.className,
                            profile = serial,
                            paused = paused,
                            category = categoryOf(info.applicationInfo),
                        )
                    }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase() }
            }.getOrDefault(emptyList())
        }
    }

    /**
     * Work apps turned off (quiet mode), or the profile not unlocked yet. A
     * launch then still goes through: Android answers it with its own "Turn
     * on work apps?" dialog, which is the one way an app that is not the home
     * screen may get the profile switched back on.
     */
    fun isPaused(users: UserManager, user: UserHandle): Boolean =
        runCatching { users.isQuietModeEnabled(user) || !users.isUserUnlocked(user) }.getOrDefault(false)

    private fun categoryOf(info: ApplicationInfo?): Int =
        if (info != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) info.category else -1

    /** The handle of the profile with this [serial], or null when it is gone. */
    fun profileHandle(context: Context, serial: Long): UserHandle? =
        runCatching { context.getSystemService(UserManager::class.java)?.getUserForSerialNumber(serial) }.getOrNull()

    /**
     * The launcher activities of one app in another profile, for its page.
     * [LauncherApps] lists only the entry points, which every launcher may
     * start; the private screens of another profile stay out of reach.
     */
    suspend fun loadProfileActivities(
        context: Context,
        app: LauncherApp,
    ): List<LauncherActivity> = withContext(Dispatchers.IO) {
        val serial = app.profile ?: return@withContext emptyList()
        runCatching {
            val user = profileHandle(context, serial) ?: return@runCatching emptyList()
            val launcher = context.getSystemService(LauncherApps::class.java) ?: return@runCatching emptyList()
            launcher.getActivityList(app.packageName, user).map { info ->
                LauncherActivity(
                    packageName = info.componentName.packageName,
                    className = info.componentName.className,
                    label = info.label.toString().ifBlank { info.componentName.className.substringAfterLast('.') },
                    exported = true,
                    enabled = true,
                    profile = serial,
                )
            }.sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    /**
     * Apps grouped by Play category for the one-tap folders, named by
     * [titleFor] (Android's own localized "Games", "Social", …). Apps that
     * name no category, and categories [titleFor] has no name for, are left
     * out. Groups come in the order their first app does.
     */
    fun categoryGroups(apps: List<LauncherApp>, titleFor: (Int) -> String?): Map<String, List<String>> {
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (app in apps) {
            if (app.category < 0) continue
            val title = titleFor(app.category)?.takeIf { it.isNotBlank() } ?: continue
            groups.getOrPut(title) { mutableListOf() } += app.key
        }
        return groups
    }

    /**
     * The browsing grid: [folders] first, in their order, each with the apps
     * of [sorted] filed in it (in the folder's own order), then every app in
     * no folder, in [sorted]'s order. A folder none of whose apps is in
     * [sorted] (all uninstalled, or hidden) is left out but stays saved.
     */
    fun arrangeGrid(sorted: List<LauncherApp>, folders: List<LauncherFolder>): List<LauncherCell> {
        if (folders.isEmpty()) return sorted.map { LauncherCell.App(it) }
        val byKey = sorted.associateBy { it.key }
        val filed = HashSet<String>()
        val cells = ArrayList<LauncherCell>(sorted.size)
        for (folder in folders) {
            val apps = folder.apps.mapNotNull { key -> byKey[key]?.takeIf { filed.add(key) } }
            if (apps.isNotEmpty()) cells += LauncherCell.Folder(folder, apps)
        }
        sorted.filterTo(ArrayList()) { it.key !in filed }.mapTo(cells) { LauncherCell.App(it) }
        return cells
    }

    /**
     * Every activity declared by [packageName], exported-and-enabled first.
     * Empty when the package is not visible or declares none.
     */
    suspend fun loadActivities(
        pm: PackageManager,
        packageName: String,
    ): List<LauncherActivity> = withContext(Dispatchers.IO) {
        val activities = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES).activities
        }.getOrNull() ?: return@withContext emptyList()
        activities
            .map { info ->
                LauncherActivity(
                    packageName = info.packageName,
                    className = info.name,
                    label = info.loadLabel(pm).toString()
                        .ifBlank { info.name.substringAfterLast('.') },
                    exported = info.exported,
                    enabled = info.enabled,
                    permission = info.permission,
                )
            }
            .sortedWith(
                compareByDescending<LauncherActivity> { it.exported && it.enabled }
                    .thenBy { it.label.lowercase() },
            )
    }

    /** Case-insensitive filter over label and package name. */
    fun filterApps(apps: List<LauncherApp>, query: String): List<LauncherApp> {
        val q = query.trim()
        if (q.isEmpty()) return apps
        return apps.filter {
            it.label.contains(q, ignoreCase = true) ||
                it.packageName.contains(q, ignoreCase = true)
        }
    }

    /**
     * The grid order: pinned apps lead in the user's pin order, then the rest
     * alphabetically or by recency, per the sort setting. [recents] is
     * most-recent-first app keys ([LauncherApp.key]), as is [pinned].
     */
    fun sortApps(
        apps: List<LauncherApp>,
        recentFirst: Boolean,
        pinned: List<String>,
        recents: List<String>,
    ): List<LauncherApp> {
        val byKey = apps.associateBy { it.key }
        val lead = pinned.mapNotNull { byKey[it] }
        val pinnedSet = pinned.toSet()
        val rest = apps.filter { it.key !in pinnedSet }
        val ordered = if (recentFirst) {
            val rank = recents.withIndex().associate { (i, key) -> key to i }
            rest.sortedWith(
                compareBy<LauncherApp> { rank[it.key] ?: Int.MAX_VALUE }
                    .thenBy { it.label.lowercase() },
            )
        } else {
            rest
        }
        return lead + ordered
    }
}
