package com.wasimaster.wmkeyboard.app.lock

import com.wasimaster.wmkeyboard.app.SettingsRouteIcons
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry names routes, string resources and call sites as plain strings,
 * and nothing but a test connects the three. Each half can be renamed without
 * the other noticing, and both failures are silent on a device: a lock whose
 * route no longer exists simply never fires, and a checkbox whose call site
 * was never wired looks like it works and protects nothing.
 */
class AppLockTargetsTest {

    private val mainActivity: String by lazy {
        File("src/main/java/com/wasimaster/wmkeyboard/app/MainActivity.kt").readText()
    }

    /** Every Kotlin source in the app module, for the call-site sweep. */
    private val appSources: List<File> by lazy {
        File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private val registrySource: String by lazy {
        File("src/main/java/com/wasimaster/wmkeyboard/app/lock/AppLockTargets.kt").readText()
    }

    private val stringNames: Set<String> by lazy {
        Regex("""<string name="([^"]+)"""")
            .findAll(
                File("src/main/res/values").listFiles()
                    ?.filter { it.extension == "xml" }
                    ?.joinToString("\n") { it.readText() }
                    .orEmpty(),
            )
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun `every locked screen is a real destination`() {
        val missing = AppLockTargets.all
            .filter { it.kind == LockKind.SCREEN }
            .mapNotNull { it.route }
            .filterNot { mainActivity.contains("composable(\"$it\")") }
        assertEquals("locked routes with no NavHost destination", emptyList<String>(), missing)
    }

    @Test
    fun `a route under a locked screen inherits the lock`() {
        // Splitting a locked screen into sub-pages must not open the
        // sub-pages: the gate looks routes up by exact string, so a child
        // route the registry has never heard of would otherwise get no lock.
        val backup = AppLockTargets.screen("backup")
        assertTrue("backup is not locked; the fixture below is meaningless", backup != null)
        assertEquals(backup, AppLockTargets.screen("backup/auto"))
        assertEquals(backup, AppLockTargets.screen("backup/destination/s3"))
        assertEquals(AppLockTargets.screen("ai_history"), AppLockTargets.screen("ai_history/log"))
        assertEquals(AppLockTargets.SELF, AppLockTargets.screen("${AppLockTargets.ROUTE}/targets"))
        // The walk is by path segment, not by prefix string: "backups" is
        // not under "backup", and an unlocked parent stays unlocked.
        assertEquals(null, AppLockTargets.screen("backups"))
        assertEquals(null, AppLockTargets.screen("typing/corrections"))
        assertEquals(null, AppLockTargets.screen("/backup"))
    }

    @Test
    fun `the configurator is a real destination`() {
        assertTrue(
            "the applock route has no destination",
            mainActivity.contains("composable(AppLockTargets.ROUTE)") ||
                mainActivity.contains("composable(\"${AppLockTargets.ROUTE}\")"),
        )
    }

    @Test
    fun `every locked screen has a glyph`() {
        // The gate draws the route's own icon, and the configurator row
        // borrows it too, so a route missing from the table draws blank.
        val missing = (AppLockTargets.all + AppLockTargets.SELF)
            .filter { it.kind == LockKind.SCREEN }
            .mapNotNull { it.route }
            .filterNot { it in SettingsRouteIcons }
        assertEquals("locked routes with no icon", emptyList<String>(), missing)
    }

    @Test
    fun `every locked action is wired to a call site`() {
        // The half that cannot be seen by looking at the configurator: a
        // target listed here but never passed as `lock =` anywhere draws a
        // checkbox that protects nothing at all.
        val wired = appSources.asSequence()
            .flatMap { file ->
                Regex("""AppLockTargets\["([^"]+)"]""").findAll(file.readText())
            }
            .map { it.groupValues[1] }
            .toSet()
        val unwired = AppLockTargets.all
            .filter { it.kind == LockKind.ACTION }
            .map { it.id }
            .filterNot { it in wired }
        assertEquals("lockable actions with no call site", emptyList<String>(), unwired)
    }

    @Test
    fun `every id a call site names is in the registry`() {
        val named = appSources.asSequence()
            .flatMap { file ->
                Regex("""AppLockTargets\["([^"]+)"]""").findAll(file.readText())
            }
            .map { it.groupValues[1] }
            .toSet()
        val unknown = named.filter { AppLockTargets[it] == null }
        assertEquals("call sites naming an id the registry does not have", emptyList<String>(), unknown)
    }

    @Test
    fun `ids are unique and stable-looking`() {
        val ids = (AppLockTargets.all + AppLockTargets.SELF).map { it.id }
        assertEquals("duplicate target ids", ids.size, ids.toSet().size)
        val malformed = ids.filterNot { it.matches(Regex("[a-z][a-z0-9_]*")) }
        assertEquals("ids that are not lower snake case", emptyList<String>(), malformed)
    }

    /**
     * Read off the source rather than the compiled ids, the same way
     * `SettingsSearchIndexTest` does: a resource id is an opaque int in a JVM
     * test, so the names are only visible in the text that produced them.
     */
    @Test
    fun `every label names a string this module defines`() {
        val named = Regex("""R\.string\.([a-z0-9_]+)""")
            .findAll(registrySource)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        assertTrue("no R.string names found in the registry", named.isNotEmpty())
        val missing = named.filterNot { it in stringNames }
        assertEquals("registry labels with no resource", emptyList<String>(), missing)
    }

    @Test
    fun `no label is the zero placeholder`() {
        val blank = (AppLockTargets.all + AppLockTargets.SELF).filter { it.label == 0 }.map { it.id }
        assertEquals("targets with no label", emptyList<String>(), blank)
    }

    @Test
    fun `the configurator cannot be unticked`() {
        assertFalse(
            "SELF is listed as a checkbox, so the lock on the lock can be switched off",
            AppLockTargets.all.contains(AppLockTargets.SELF),
        )
        assertFalse("SELF is in the default picks", AppLockTargets.SELF.id in AppLockTargets.defaultIds)
    }

    @Test
    fun `screens carry a route and actions do not`() {
        for (target in AppLockTargets.all + AppLockTargets.SELF) {
            if (target.kind == LockKind.SCREEN) {
                assertTrue("${target.id} is a screen with no route", !target.route.isNullOrEmpty())
            } else {
                assertEquals("${target.id} is an action carrying a route", null, target.route)
            }
        }
    }

    @Test
    fun `the defaults are a subset of the registry`() {
        val stray = AppLockTargets.defaultIds.filterNot { AppLockTargets[it] != null }
        assertEquals("default ids that name nothing", emptyList<String>(), stray)
        assertTrue("no target is on by default", AppLockTargets.defaultIds.isNotEmpty())
    }

    @Test
    fun `the registry is the only place a lock id is spelled out`() {
        // A `lock = LockTarget(...)` built at a call site would be invisible to
        // the configurator: the user would have no way to untick it.
        // Anchored on a word boundary, or `setAppLockTarget(` matches it.
        val constructorCall = Regex("""(?<![A-Za-z0-9_])LockTarget\(""")
        val inlineBuilds = appSources
            .filterNot { it.path.endsWith("AppLockTargets.kt") }
            .filter { constructorCall.containsMatchIn(it.readText()) }
            .map { it.name }
        assertEquals("LockTarget built outside the registry", emptyList<String>(), inlineBuilds)
    }

    @Test
    fun `every target appears in exactly one configurator group`() {
        val grouped = AppLockTargets.grouped.flatMap { it.second }
        assertEquals("a target is missing from the grouped list", AppLockTargets.all.size, grouped.size)
        assertEquals("a target is in two groups", grouped.size, grouped.toSet().size)
    }
}
