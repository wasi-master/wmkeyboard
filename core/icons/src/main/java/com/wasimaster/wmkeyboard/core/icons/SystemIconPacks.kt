package com.wasimaster.wmkeyboard.core.icons

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import android.util.Xml
import com.wasimaster.wmkeyboard.core.util.StartedActivities
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

    /**
     * [packageName]'s index, read once per process and kept; empty for a pack
     * that cannot be read.
     *
     * One reader per pack: the App icons page and the launcher grid ask for
     * every app's icon at once, and before this each of those callers missed
     * the cache together and parsed the whole `appfilter.xml` side by side. A
     * large pack (a Pix Material pack, in one report) read by dozens of IO threads at
     * once held so much memory that the app stopped answering. The rest now
     * wait for the first and share its result.
     */
    fun index(context: Context, packageName: String): SystemPackIndex {
        lastUseNanos = System.nanoTime()
        synchronized(indexes) { indexes[packageName]?.let { return it } }
        val gate = synchronized(reading) { reading.getOrPut(packageName) { Any() } }
        synchronized(gate) {
            synchronized(indexes) { indexes[packageName]?.let { return it } }
            val read = runCatching { readIndex(context, packageName) }.getOrNull() ?: SystemPackIndex.EMPTY
            synchronized(indexes) { indexes[packageName] = read }
            // The App icons page reads packs too, in the keyboard's own
            // process, which outlives the settings app by days.
            StartedActivities.onAllStopped(RELEASE_KEY) { if (idle()) trimMemory() }
            return read
        }
    }

    /** One lock per pack being read, so a pack is parsed by one thread at a time. */
    private val reading = HashMap<String, Any>()

    /**
     * [name] out of [packageName] drawn into a [sizePx] square, or null when
     * the pack or the drawable is gone. Blocking: call it off the main thread.
     */
    fun drawable(context: Context, packageName: String, name: String, sizePx: Int): Bitmap? {
        if (!isPackageName(packageName) || !isDrawableName(name) || sizePx <= 0) return null
        lastUseNanos = System.nanoTime()
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

    /**
     * Lets go of every pack's index and of the decoded drawables, for when the
     * keyboard has gone idle or memory is short; the next lookup reads the
     * pack again, off the main thread like the first.
     *
     * Kept until now for the life of the process, which is the keyboard's. An
     * index is the whole of a pack's `appfilter.xml`, and Arcticons lists about
     * twelve thousand entries: some 3 MB of map nodes and strings (estimate,
     * 12,000 entries at about 250 bytes). The drawables are up to
     * [BITMAP_BUDGET] more, and the launcher grid holds its own copies of the
     * ones it shows, so the keyboard's idle release, which empties that grid's
     * cache, freed none of them while this one kept them. Every caller keeps
     * its own copy of a picture it draws, so nothing on screen goes blank.
     */
    fun trimMemory() = clear()

    /** When [index] or [drawable] was last called, from [System.nanoTime]. */
    @Volatile
    private var lastUseNanos = 0L

    /**
     * No lookup for [IDLE_NANOS]: a launcher grid being scrolled right now
     * would read its pack again on the next miss, which for a large pack is a
     * parse of a few hundred milliseconds.
     */
    private fun idle(): Boolean = System.nanoTime() - lastUseNanos > IDLE_NANOS

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

    /**
     * Two or more dot-separated runs of ASCII letters, digits and `_`: what
     * `[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+` matched, without the regex. Both
     * checks here run on every keyboard icon drawn from a pack, so this object
     * is loaded in the keyboard's process, and the two compiled patterns were
     * 7.5 KB of native memory (measured with ICU) held for the rest of it.
     */
    fun isPackageName(value: String): Boolean {
        if (value.length > 255) return false
        val parts = value.split('.')
        return parts.size >= 2 && parts.all { part -> part.isNotEmpty() && part.all { isNameChar(it) } }
    }

    /** A resource name: what a drawable is called, and safe inside an override's CSV. */
    fun isDrawableName(value: String): Boolean = value.length in 1..200 && value.all { isNameChar(it) }

    private fun isNameChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

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

    /** How long without a lookup counts as [idle]: thirty seconds. */
    private const val IDLE_NANOS = 30_000_000_000L

    private const val RELEASE_KEY = "icons.systemPacks"
}
