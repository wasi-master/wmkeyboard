package com.wasimaster.wmkeyboard.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserManager
import androidx.core.graphics.drawable.toBitmap
import com.wasimaster.wmkeyboard.core.settings.CUSTOM_LAUNCHER_SHORTCUT_PREFIX
import com.wasimaster.wmkeyboard.ime.ui.CustomButtonFaces
import org.xmlpull.v1.XmlPullParser

/**
 * One shortcut another app offers (#1213): "New incognito tab" in a browser,
 * "Scan a QR code" in a camera. [target] is what a [CustomButton] stores;
 * [icon] reads the picture only when the shortcut is picked.
 */
internal class ShortcutOffer(
    val key: String,
    val label: String,
    val appLabel: String,
    val packageName: String,
    val target: String,
    val icon: () -> Drawable?,
)

/** An app screen that makes a shortcut and hands it back: the Widgets-style "Shortcuts" list. */
internal class ShortcutMaker(
    val packageName: String,
    val activity: String,
    val label: String,
    val appLabel: String,
)

/**
 * Where the shortcuts of other apps come from. Android lets only the home
 * screen (and what it allows) read every app's shortcuts through
 * [LauncherApps.getShortcuts]; where the keyboard is let, that is the list.
 * Otherwise there are two ways that need no special standing: the shortcuts
 * an app declares in its manifest, read from its own resources and offered
 * where the screen they open is one other apps may start, and the apps'
 * "create shortcut" screens, which hand a shortcut back to whoever asked.
 */
internal object CustomShortcutSources {

    /** Whether Android lets the keyboard read and start every app's shortcuts. */
    fun canReadShortcuts(context: Context): Boolean = runCatching {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
            context.getSystemService(LauncherApps::class.java)?.hasShortcutHostPermission() == true
    }.getOrDefault(false)

    /** Every app's shortcuts this keyboard can offer, by app and then by name. Blocking: call off the main thread. */
    fun offers(context: Context): List<ShortcutOffer> {
        val list = if (canReadShortcuts(context)) launcherShortcuts(context) else manifestShortcuts(context)
        return list.filter { it.packageName != context.packageName }
            .distinctBy { it.key }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ShortcutOffer::appLabel).thenBy(ShortcutOffer::label))
    }

    /** The apps' "create shortcut" screens. Blocking. */
    fun makers(context: Context): List<ShortcutMaker> {
        val pm = context.packageManager
        return runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_CREATE_SHORTCUT), 0) }.getOrDefault(emptyList())
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                if (activity.packageName == context.packageName || !activity.exported) return@mapNotNull null
                ShortcutMaker(
                    packageName = activity.packageName,
                    activity = activity.name,
                    label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(activity.name),
                    appLabel = appLabel(pm, activity.packageName),
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ShortcutMaker::appLabel))
    }

    fun makerIntent(maker: ShortcutMaker): Intent =
        Intent(Intent.ACTION_CREATE_SHORTCUT).setClassName(maker.packageName, maker.activity)

    /**
     * What a "create shortcut" screen handed back: its intent as a target,
     * its name and its picture, or null when it gave nothing the keyboard
     * can start (a pin request only the home screen can accept).
     */
    @Suppress("DEPRECATION")
    fun fromMakerResult(context: Context, data: Intent?, maker: ShortcutMaker): MadeShortcut? {
        if (data == null) return null
        val inner = runCatching { data.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT) }.getOrNull()
        val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME).orEmpty()
        if (inner == null) return pinRequest(context, data)
        val bitmap = runCatching { data.getParcelableExtra<Bitmap>(Intent.EXTRA_SHORTCUT_ICON) }.getOrNull()
        val iconRes = runCatching {
            data.getParcelableExtra<Intent.ShortcutIconResource>(Intent.EXTRA_SHORTCUT_ICON_RESOURCE)
        }.getOrNull()
        val drawable = bitmap?.let { android.graphics.drawable.BitmapDrawable(context.resources, it) }
            ?: iconRes?.let { res ->
                runCatching {
                    val resources = context.packageManager.getResourcesForApplication(res.packageName)
                    resources.getDrawable(resources.getIdentifier(res.resourceName, null, null), null)
                }.getOrNull()
            }
        return MadeShortcut(
            target = inner.toUri(Intent.URI_INTENT_SCHEME),
            label = name.ifBlank { maker.label },
            packageName = inner.component?.packageName ?: inner.`package` ?: maker.packageName,
            icon = drawable,
        )
    }

    /**
     * Android 8 and up: an app may answer with a pin request instead. Only
     * worth anything where the keyboard can start shortcuts itself.
     */
    private fun pinRequest(context: Context, data: Intent): MadeShortcut? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !canReadShortcuts(context)) return null
        return runCatching {
            val apps = context.getSystemService(LauncherApps::class.java) ?: return@runCatching null
            val request = apps.getPinItemRequest(data) ?: return@runCatching null
            val info = request.shortcutInfo ?: return@runCatching null
            if (!request.accept()) return@runCatching null
            val serial = context.getSystemService(UserManager::class.java)?.getSerialNumberForUser(info.userHandle) ?: 0L
            MadeShortcut(
                target = "$CUSTOM_LAUNCHER_SHORTCUT_PREFIX$serial:${info.`package`}/${info.id}",
                label = (info.shortLabel ?: info.longLabel)?.toString().orEmpty(),
                packageName = info.`package`,
                icon = apps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi),
            )
        }.getOrNull()
    }

    /** Draws [drawable] into the button's picture file, so the keyboard can show it later. */
    fun saveIcon(context: Context, id: String, drawable: Drawable?) {
        val file = CustomButtonFaces.iconFile(context, id)
        if (drawable == null) {
            file.delete()
            return
        }
        runCatching {
            file.parentFile?.mkdirs()
            val bitmap = drawable.toBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    fun deleteIcon(context: Context, id: String) {
        runCatching { CustomButtonFaces.iconFile(context, id).delete() }
    }

    // ---- LauncherApps, where the keyboard may read shortcuts ----

    private fun launcherShortcuts(context: Context): List<ShortcutOffer> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return emptyList()
        val apps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val users = context.getSystemService(UserManager::class.java)
        val pm = context.packageManager
        val density = context.resources.displayMetrics.densityDpi
        val profiles = runCatching { apps.profiles }.getOrNull().orEmpty().ifEmpty { listOf(Process.myUserHandle()) }
        return profiles.flatMap { user ->
            val serial = users?.getSerialNumberForUser(user) ?: 0L
            val query = LauncherApps.ShortcutQuery().setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
            )
            runCatching { apps.getShortcuts(query, user) }.getOrNull().orEmpty()
                .filter { it.isEnabled }
                .map { info ->
                    ShortcutOffer(
                        key = "$serial:${info.`package`}/${info.id}",
                        label = (info.shortLabel ?: info.longLabel ?: info.id).toString(),
                        appLabel = appLabel(pm, info.`package`),
                        packageName = info.`package`,
                        target = "$CUSTOM_LAUNCHER_SHORTCUT_PREFIX$serial:${info.`package`}/${info.id}",
                        icon = { runCatching { apps.getShortcutIconDrawable(info, density) }.getOrNull() },
                    )
                }
        }
    }

    // ---- the manifest's static shortcuts ----

    private fun manifestShortcuts(context: Context): List<ShortcutOffer> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return emptyList()
        val pm = context.packageManager
        val launchers = runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.GET_META_DATA,
            )
        }.getOrDefault(emptyList())
        return launchers.flatMap { info ->
            val activity = info.activityInfo ?: return@flatMap emptyList()
            val xml = activity.metaData?.getInt(SHORTCUTS_META, 0) ?: 0
            if (xml == 0) return@flatMap emptyList()
            runCatching {
                val res = pm.getResourcesForApplication(activity.applicationInfo)
                val appName = appLabel(pm, activity.packageName)
                parseShortcuts(res, res.getXml(xml), activity.packageName)
                    .filter { startable(pm, it.intent) }
                    .map { shortcut ->
                        ShortcutOffer(
                            key = "${activity.packageName}/${shortcut.id}",
                            label = shortcut.label,
                            appLabel = appName,
                            packageName = activity.packageName,
                            target = shortcut.intent.toUri(Intent.URI_INTENT_SCHEME),
                            icon = {
                                shortcut.iconRes.takeIf { it != 0 }?.let { id ->
                                    runCatching {
                                        res.getDrawableForDensity(id, context.resources.displayMetrics.densityDpi, null)
                                    }.getOrNull()
                                }
                            },
                        )
                    }
            }.getOrDefault(emptyList())
        }
    }

    private class Parsed(val id: String, val label: String, val iconRes: Int, val intent: Intent)

    /**
     * Reads an `android.app.shortcuts` XML: each enabled `<shortcut>` with
     * its id, short label, icon and last `<intent>` (the one a launcher
     * brings to the front). Attributes are matched by their framework
     * resource ids, which survive a shrunk APK that drops their names.
     */
    private fun parseShortcuts(res: Resources, parser: XmlResourceParser, owner: String): List<Parsed> {
        val out = ArrayList<Parsed>()
        var id: String? = null
        var label = ""
        var icon = 0
        var enabled = true
        var intent: Intent? = null
        parser.use { p ->
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "shortcut" -> {
                            id = p.stringAttr(res, android.R.attr.shortcutId)
                            label = p.stringAttr(res, android.R.attr.shortcutShortLabel)
                                ?: p.stringAttr(res, android.R.attr.shortcutLongLabel).orEmpty()
                            icon = p.resourceAttr(android.R.attr.icon)
                            enabled = p.booleanAttr(android.R.attr.enabled) ?: true
                            intent = null
                        }
                        "intent" -> if (id != null) {
                            val action = p.stringAttr(res, android.R.attr.action) ?: Intent.ACTION_VIEW
                            val pkg = p.stringAttr(res, android.R.attr.targetPackage) ?: owner
                            val cls = p.stringAttr(res, android.R.attr.targetClass)
                            val data = p.stringAttr(res, android.R.attr.data)
                            intent = Intent(action).apply {
                                if (cls != null) component = ComponentName(pkg, cls) else setPackage(pkg)
                                if (data != null) setData(Uri.parse(data))
                            }
                        }
                        "categories" -> p.stringAttr(res, android.R.attr.name)?.let { intent?.addCategory(it) }
                        "extra" -> {
                            val name = p.stringAttr(res, android.R.attr.name)
                            val value = p.stringAttr(res, android.R.attr.value)
                            if (name != null && value != null) intent?.putExtra(name, value)
                        }
                    }
                    XmlPullParser.END_TAG -> if (p.name == "shortcut") {
                        val sid = id
                        val target = intent
                        if (sid != null && target != null && enabled && label.isNotBlank()) {
                            out += Parsed(sid, label, icon, target)
                        }
                        id = null
                        intent = null
                    }
                }
                event = p.next()
            }
        }
        return out
    }

    private fun XmlResourceParser.indexOf(attr: Int): Int {
        for (i in 0 until attributeCount) if (getAttributeNameResource(i) == attr) return i
        return -1
    }

    private fun XmlResourceParser.stringAttr(res: Resources, attr: Int): String? {
        val i = indexOf(attr)
        if (i < 0) return null
        val ref = getAttributeResourceValue(i, 0)
        if (ref != 0) return runCatching { res.getString(ref) }.getOrNull()
        return getAttributeValue(i)?.takeIf { it.isNotEmpty() }
    }

    private fun XmlResourceParser.resourceAttr(attr: Int): Int {
        val i = indexOf(attr)
        return if (i < 0) 0 else getAttributeResourceValue(i, 0)
    }

    private fun XmlResourceParser.booleanAttr(attr: Int): Boolean? {
        val i = indexOf(attr)
        return if (i < 0) null else getAttributeBooleanValue(i, true)
    }

    /** Whether another app may start [intent]: its screen is there and exported. */
    private fun startable(pm: PackageManager, intent: Intent): Boolean = runCatching {
        val component = intent.component
        if (component != null) {
            pm.getActivityInfo(component, 0).exported
        } else {
            pm.resolveActivity(intent, 0)?.activityInfo?.exported == true
        }
    }.getOrDefault(false)

    private fun appLabel(pm: PackageManager, packageName: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private const val SHORTCUTS_META = "android.app.shortcuts"
    private const val ICON_PX = 192
}

/** A shortcut a "create shortcut" screen handed back, ready to become a button. */
internal class MadeShortcut(
    val target: String,
    val label: String,
    val packageName: String,
    val icon: Drawable?,
)
