package com.wasimaster.wmkeyboard.core.icons

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/** A launcher icon pack installed on the phone (#1275), such as Arcticons. */
data class SystemIconPack(val packageName: String, val label: String)

/**
 * What one installed pack draws, read from its `appfilter.xml`: which of its
 * drawables stands for which app, and the drawables it offers to pick from.
 *
 * [byComponent] is keyed `package/activity`, the way the file names an app.
 * [byPackage] is the first drawable the file gives any activity of a package,
 * for an app whose launcher activity has been renamed since the pack was made.
 * [drawables] is the pack's own list (`drawable.xml`) when it ships one, else
 * every drawable `appfilter.xml` names, in file order and without repeats.
 */
class SystemPackIndex(
    val byComponent: Map<String, String>,
    val byPackage: Map<String, String>,
    val drawables: List<String>,
) {
    /** The drawable this pack gives [packageName]'s [activityName], or its package's, or null. */
    fun drawableFor(packageName: String, activityName: String?): String? =
        activityName?.let { byComponent["$packageName/$it"] } ?: byPackage[packageName]

    /** Every drawable the pack gives some activity of [packageName], for the picker to lead with. */
    fun drawablesFor(packageName: String): List<String> {
        val prefix = "$packageName/"
        return byComponent.entries.filter { it.key.startsWith(prefix) }.map { it.value }.distinct()
    }

    companion object {
        val EMPTY = SystemPackIndex(emptyMap(), emptyMap(), emptyList())
    }
}

/**
 * Icon packs made for home-screen launchers (#1275): ADW, Nova, Apex, Go and
 * the rest all read the same format, and a pack declares itself with one of
 * their intent actions. The keyboard finds packs through those actions (the
 * manifest's `<queries>` lists them, so Android 11 and later let it see them)
 * and reads a pack's resources directly; nothing is copied out of it.
 *
 * A pack is someone else's APK, so everything here is defensive: a file that
 * will not parse is an empty index, a drawable that will not load is null, and
 * neither ever reaches the caller as an exception.
 */
object SystemIconPacks {

    /** The actions launcher icon packs declare an activity for. */
    val ACTIONS: List<String> = listOf(
        "org.adw.launcher.THEMES",
        "org.adw.ActivityStarter.THEMES",
        "com.novalauncher.THEME",
        "com.teslacoilsw.launcher.THEME",
        "com.gau.go.launcherex.theme",
        "com.anddoes.launcher.THEME",
        "com.fede.launcher.THEME_ICONPACK",
        "com.dlto.atom.launcher.THEME",
        "ginlemon.smartlauncher.THEMES",
        "com.lge.launcher2.THEME",
    )

    private val indexes = HashMap<String, SystemPackIndex>()

    /**
     * Decoded drawables by `package/name@size`, bounded in bytes. Lazy, so the
     * plain string helpers below run in a JVM unit test with no Android.
     */
    private val bitmaps by lazy {
        object : LruCache<String, Bitmap>(BITMAP_BUDGET) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }

    /** Every installed pack, by name. Empty when there is none or the lookup fails. */
    fun installed(context: Context): List<SystemIconPack> {
        val pm = context.packageManager
        val seen = LinkedHashMap<String, SystemIconPack>()
        for (action in ACTIONS) {
            val found = runCatching { pm.queryIntentActivities(Intent(action), 0) }.getOrNull().orEmpty()
            for (info in found) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg in seen || pkg == context.packageName) continue
                val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                        .getOrDefault(pkg)
                seen[pkg] = SystemIconPack(pkg, label)
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    /** [packageName]'s index, read once per process and kept; empty for a pack that cannot be read. */
    fun index(context: Context, packageName: String): SystemPackIndex {
        synchronized(indexes) { indexes[packageName]?.let { return it } }
        val read = runCatching { readIndex(context, packageName) }.getOrNull() ?: SystemPackIndex.EMPTY
        synchronized(indexes) { indexes[packageName] = read }
        return read
    }

    /**
     * [name] out of [packageName] drawn into a [sizePx] square, or null when
     * the pack or the drawable is gone. Blocking: call it off the main thread.
     */
    fun drawable(context: Context, packageName: String, name: String, sizePx: Int): Bitmap? {
        if (!isPackageName(packageName) || !isDrawableName(name) || sizePx <= 0) return null
        val key = "$packageName/$name@$sizePx"
        bitmaps.get(key)?.let { return it }
        val bitmap = runCatching {
            val res = context.packageManager.getResourcesForApplication(packageName)
            val id = resourceId(res, name, packageName)
            if (id == 0) return@runCatching null
            val density = context.resources.displayMetrics.densityDpi
            val drawable = res.getDrawableForDensity(id, density, null) ?: return@runCatching null
            val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(Canvas(out))
            out
        }.getOrNull() ?: return null
        bitmaps.put(key, bitmap)
        return bitmap
    }

    /** Forgets every pack read so far, for a package added, removed or replaced. */
    fun clear() {
        synchronized(indexes) { indexes.clear() }
        bitmaps.evictAll()
    }

    // ---- sources ---------------------------------------------------------

    /** The override source for [name] out of [packageName]; see [IconOverrides.SYSTEM_PACK_PREFIX]. */
    fun source(packageName: String, name: String): String =
        IconOverrides.SYSTEM_PACK_PREFIX + packageName + "/" + name

    /** The pack and drawable a `s:` source names, or null when it is no such source or malformed. */
    fun parseSource(source: String?): Pair<String, String>? {
        if (source == null || !source.startsWith(IconOverrides.SYSTEM_PACK_PREFIX)) return null
        val body = source.removePrefix(IconOverrides.SYSTEM_PACK_PREFIX)
        val slash = body.indexOf('/')
        if (slash <= 0) return null
        val pkg = body.substring(0, slash)
        val name = body.substring(slash + 1)
        return if (isPackageName(pkg) && isDrawableName(name)) pkg to name else null
    }

    /**
     * `ComponentInfo{com.app/com.app.Main}` as `com.app/com.app.Main`, with a
     * relative activity (`.Main`) spelled out. Null for anything else, which
     * packs use for calendar and theme entries the keyboard has no use for.
     */
    fun parseComponent(raw: String?): String? {
        val inner = raw?.trim()
            ?.removePrefix("ComponentInfo{")
            ?.removeSuffix("}")
            ?: return null
        val slash = inner.indexOf('/')
        if (slash <= 0 || slash == inner.lastIndex) return null
        val pkg = inner.substring(0, slash).trim()
        val activity = inner.substring(slash + 1).trim().let { if (it.startsWith('.')) pkg + it else it }
        return if (isPackageName(pkg) && activity.isNotEmpty()) "$pkg/$activity" else null
    }

    private val PACKAGE = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
    private val DRAWABLE = Regex("[A-Za-z0-9_]{1,200}")

    fun isPackageName(value: String): Boolean = value.length <= 255 && PACKAGE.matches(value)

    /** A resource name: what a drawable is called, and safe inside an override's CSV. */
    fun isDrawableName(value: String): Boolean = DRAWABLE.matches(value)

    // ---- reading a pack --------------------------------------------------

    private fun resourceId(res: Resources, name: String, packageName: String): Int {
        @Suppress("DiscouragedApi")
        val drawable = res.getIdentifier(name, "drawable", packageName)
        if (drawable != 0) return drawable
        @Suppress("DiscouragedApi")
        return res.getIdentifier(name, "mipmap", packageName)
    }

    private fun readIndex(context: Context, packageName: String): SystemPackIndex {
        val pm = context.packageManager
        val res = try {
            pm.getResourcesForApplication(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return SystemPackIndex.EMPTY
        }
        val byComponent = LinkedHashMap<String, String>()
        val byPackage = HashMap<String, String>()
        val named = LinkedHashSet<String>()
        withParser(res, packageName, "appfilter") { parser ->
            eachItem(parser) {
                val drawable = parser.getAttributeValue(null, "drawable")?.trim()
                val component = parseComponent(parser.getAttributeValue(null, "component"))
                if (drawable != null && isDrawableName(drawable)) {
                    named.add(drawable)
                    if (component != null && byComponent.size < MAX_ITEMS) {
                        byComponent.putIfAbsent(component, drawable)
                        byPackage.putIfAbsent(component.substringBefore('/'), drawable)
                    }
                }
            }
        }
        // The pack's own list of what it draws, which also holds icons no app
        // filter entry points at yet: the ones a user is likeliest to want for
        // an app the pack has not themed.
        val listed = LinkedHashSet<String>()
        withParser(res, packageName, "drawable") { parser ->
            eachItem(parser) {
                val drawable = parser.getAttributeValue(null, "drawable")?.trim()
                if (drawable != null && isDrawableName(drawable) && listed.size < MAX_ITEMS) listed.add(drawable)
            }
        }
        val drawables = (if (listed.isNotEmpty()) listed else named).toList()
        return SystemPackIndex(byComponent, byPackage, drawables)
    }

    /** Opens `res/xml/<name>.xml`, or `assets/<name>.xml` when the pack keeps it there. */
    private fun withParser(res: Resources, packageName: String, name: String, block: (XmlPullParser) -> Unit) {
        @Suppress("DiscouragedApi")
        val id = res.getIdentifier(name, "xml", packageName)
        if (id != 0) {
            runCatching { res.getXml(id).readClosing(block) }
            return
        }
        runCatching {
            res.assets.open("$name.xml").use { stream ->
                val parser = Xml.newPullParser()
                parser.setInput(stream, null)
                block(parser)
            }
        }
    }

    private inline fun eachItem(parser: XmlPullParser, onItem: () -> Unit) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") onItem()
            event = parser.next()
        }
    }

    private fun android.content.res.XmlResourceParser.readClosing(block: (XmlPullParser) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    /** A cap on one file's entries; Arcticons, among the largest, lists about twelve thousand. */
    private const val MAX_ITEMS = 50_000

    /** Decoded icons kept in memory, in bytes. */
    private const val BITMAP_BUDGET = 6 * 1024 * 1024
}
