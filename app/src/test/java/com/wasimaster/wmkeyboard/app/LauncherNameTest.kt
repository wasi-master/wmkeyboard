package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [LauncherName] switches the launcher label by enabling one of two
 * `<activity-alias>`es, and nothing at compile time ties its constants to the
 * manifest. Every way the two drift is silent until a user reaches it: a
 * renamed alias makes the switch throw, strands whoever had switched, and
 * drops pinned home-screen icons; a second enabled entry puts two icons in the
 * app drawer; an alias declared above its target makes the APK uninstallable.
 *
 * These read the real manifest and check the two still agree.
 */
class LauncherNameTest {

    /** Unit tests run with the module directory as the working directory. */
    private val manifest = File("src/main/AndroidManifest.xml").readText()
        // Comments in this manifest quote the elements they explain.
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private val launcher = """<category android:name="android.intent.category.LAUNCHER" />"""

    /** Opening-tag attributes and body of each alias, keyed by its android:name. */
    private val aliases: Map<String, Pair<String, String>> =
        Regex("""<activity-alias\s([^>]*)>(.*?)</activity-alias>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest)
            .associate { match ->
                val attributes = match.groupValues[1]
                attribute(attributes, "name") to (attributes to match.groupValues[2])
            }

    private fun attribute(attributes: String, name: String): String =
        Regex("""android:$name="([^"]*)"""").find(attributes)?.groupValues?.get(1).orEmpty()

    private fun alias(name: String): Pair<String, String> {
        assertTrue("no <activity-alias> named $name in AndroidManifest.xml", name in aliases)
        return aliases.getValue(name)
    }

    @Test
    fun `both names are launcher aliases of the settings activity`() {
        for (name in listOf(LauncherName.FULL_ALIAS, LauncherName.SHORT_ALIAS)) {
            val (attributes, body) = alias(name)
            assertEquals(
                "$name must open MainActivity",
                MainActivity::class.java.name,
                attribute(attributes, "targetActivity"),
            )
            assertEquals("$name must be exported to be launchable", "true", attribute(attributes, "exported"))
            assertTrue("$name has no MAIN action", "android.intent.action.MAIN" in body)
            assertTrue("$name has no LAUNCHER category", launcher in body)
            // The icon's menu is the user's now, published at run time (AppIconMenu):
            // a static one left here could never be taken out of it.
            assertFalse("$name still carries static shortcuts", "android.app.shortcuts" in body)
        }
    }

    @Test
    fun `out of the box exactly the full name is enabled`() {
        assertEquals("true", attribute(alias(LauncherName.FULL_ALIAS).first, "enabled"))
        assertEquals("false", attribute(alias(LauncherName.SHORT_ALIAS).first, "enabled"))
        assertFalse("the reset value must be what the manifest enables", LauncherName.DEFAULT_SHORT)
        assertTrue("a fresh install shows its icon", LauncherName.DEFAULT_SHOWN)
    }

    @Test
    fun `the aliases are the only launcher entries`() {
        // <queries> asks for other apps' launcher activities; that one is not ours.
        val application = manifest.replace(Regex("<queries>.*?</queries>", RegexOption.DOT_MATCHES_ALL), "")
        // A tool's own app-list entry (ToolAppList) is one too, each off in the
        // manifest; ToolShortcutsTest holds those.
        val tools = aliases.filterKeys { it.startsWith(ToolAppList.ALIAS_PREFIX) }.values.count { launcher in it.second }
        assertEquals("LAUNCHER categories outside <queries>", 2 + tools, Regex(Regex.escape(launcher)).findAll(application).count())
        val mainActivity = Regex(
            """<activity\s[^>]*android:name="${Regex.escape(MainActivity::class.java.name)}".*?</activity>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(manifest)?.value.orEmpty()
        assertTrue("MainActivity is missing from the manifest", mainActivity.isNotEmpty())
        assertFalse("MainActivity must not be a launcher entry of its own", launcher in mainActivity)
    }

    @Test
    fun `each alias follows the activity it targets`() {
        // PackageManager refuses to install an APK whose alias names a target
        // declared further down the file.
        val target = manifest.indexOf("""android:name="${MainActivity::class.java.name}"""")
        for (name in listOf(LauncherName.FULL_ALIAS, LauncherName.SHORT_ALIAS)) {
            val at = manifest.indexOf("""android:name="$name"""")
            assertTrue("$name is declared above MainActivity", target in 0 until at)
        }
    }

    @Test
    fun `the labels are the two app names`() {
        assertEquals("@string/app_name", attribute(alias(LauncherName.FULL_ALIAS).first, "label"))
        assertEquals("@string/app_name_short", attribute(alias(LauncherName.SHORT_ALIAS).first, "label"))
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(
            "app_name_short must read WMK",
            Regex("""<string name="app_name_short"[^>]*>WMK</string>""").containsMatchIn(strings),
        )
    }

    /**
     * The television entry. A TV home screen lists LEANBACK_LAUNCHER and
     * nothing else, so losing this alias — or its banner, without which the
     * launcher draws an empty tile — makes the settings app unreachable on a
     * Google TV with no visible sign that anything is wrong.
     *
     * It is also the one launcher entry that must stay enabled whichever app
     * name the user picked, which is why it is not a category on either of the
     * two aliases above.
     */
    @Test
    fun `the television alias is enabled, banner and all`() {
        val (attributes, body) = alias(TV_ALIAS)
        assertEquals(MainActivity::class.java.name, attribute(attributes, "targetActivity"))
        assertEquals("true", attribute(attributes, "enabled"))
        assertEquals("true", attribute(attributes, "exported"))
        assertEquals("@drawable/tv_banner", attribute(attributes, "banner"))
        assertTrue("$TV_ALIAS has no MAIN action", "android.intent.action.MAIN" in body)
        assertTrue("$TV_ALIAS is not a leanback launcher entry", leanback in body)
        assertTrue("$TV_ALIAS must follow MainActivity", manifest.indexOf(TV_ALIAS) > target)
    }

    /** Exactly one, and it is that alias: two would put two tiles on the TV home screen. */
    @Test
    fun `the television alias is the only leanback entry`() {
        assertEquals(1, Regex(Regex.escape(leanback)).findAll(manifest).count())
    }

    /**
     * Google TV filters on a *not*-required touchscreen, and the Play Store
     * reads a leanback launcher entry with no `android.software.leanback`
     * declaration as "TV only" and stops offering the app to phones. Both lines
     * are one edit away from silently halving the install base in one direction
     * or the other.
     *
     * The two screen orientations are there because a library activity locked
     * to portrait implies a required portrait screen, which Play's Android TV
     * review rejects.
     */
    @Test
    fun `the manifest declares a television can install this`() {
        val features = listOf(
            "android.hardware.touchscreen",
            "android.software.leanback",
            "android.hardware.screen.portrait",
            "android.hardware.screen.landscape",
        )
        for (feature in features) {
            assertTrue(
                "$feature must be declared not required",
                Regex(
                    """<uses-feature\s+android:name="${Regex.escape(feature)}"\s+""" +
                        """android:required="false"\s*/>""",
                ).containsMatchIn(manifest),
            )
        }
    }

    private val leanback =
        """<category android:name="android.intent.category.LEANBACK_LAUNCHER" />"""

    private val target = manifest.indexOf("""android:name="${MainActivity::class.java.name}"""")

    private companion object {
        const val TV_ALIAS = "com.wasimaster.wmkeyboard.app.LauncherTelevision"
    }
}
