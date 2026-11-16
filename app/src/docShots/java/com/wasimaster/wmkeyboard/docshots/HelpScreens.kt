package com.wasimaster.wmkeyboard.docshots

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.wasimaster.wmkeyboard.app.MainActivity
import com.wasimaster.wmkeyboard.app.SettingsHighlight
import com.wasimaster.wmkeyboard.core.settings.SettingLinkMode
import com.wasimaster.wmkeyboard.core.settings.SettingsDepth
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Help pictures of the settings app itself, for the settings that change how
 * its own screens look: the row icons, the favourites star, how deep a screen
 * goes. The keyboard's pictures are [HelpShots]'; these open [MainActivity] on
 * a route instead, and leave the same files for tools/helpvisuals/build.py,
 * which cuts each pair to the rows that differ.
 *
 * Run under the same flags as [HelpShots], with `--tests "*HelpScreens*"`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h851dp-normal-long-notround-any-440dpi-keyshidden-nonav")
class HelpScreens(
    private val visual: HelpScreen,
    private val index: Int,
    private val dark: Boolean,
    @Suppress("unused") private val name: String,
) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{3}")
        fun frames(): List<Array<Any>> {
            val only = System.getProperty("wmkb.helpShots.only")?.takeIf { it.isNotBlank() }?.toRegex()
                ?: return emptyList()
            return HELP_SCREENS
                .filter { only.containsMatchIn(it.key) }
                .flatMap { visual ->
                    visual.frames.indices.flatMap { index ->
                        shotModes().map { dark ->
                            arrayOf(visual, index, dark, "${visual.key} $index ${if (dark) "dark" else "light"}")
                        }
                    }
                }
        }
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = File(System.getProperty("wmkb.helpShots.out") ?: "build/helpshots")

    @Test
    fun capture() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        grantInternet(app)
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        SettingsHighlight.clear()
        val repo = SettingsRepository(app)
        val frame = visual.frames[index]
        runBlocking {
            repo.clearAllPreferences()
            // Past onboarding, or every link lands on the welcome screen.
            repo.setOnboardingDone(true)
            frame.seed(Seed(repo, app))
        }
        val dir = File(out, "${visual.key}/${if (dark) "dark" else "light"}").apply { mkdirs() }
        dir.listFiles { file -> file.name.startsWith("$index.") }?.forEach { it.delete() }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("wmkeyboard://settings/${frame.route}"))
            .setClass(app, MainActivity::class.java)
        androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        androidx.test.espresso.IdlingPolicies.setIdlingResourceTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        ActivityScenario.launch<android.app.Activity>(intent).use {
            settle(first = true)
            captureScreenRoboImage(File(dir, "$index.png").path)
            File(dir, "$index.json").writeText(
                buildString {
                    append("{\n")
                    append("  \"key\": \"${visual.key}\",\n")
                    append("  \"kind\": \"${visual.kind}\",\n")
                    append("  \"crop\": \"${HelpCrop.DIFF.id}\",\n")
                    append("  \"above\": 0,\n")
                    append("  \"frames\": ${visual.frames.size},\n")
                    append("  \"label\": ${frame.label?.let { "\"$it\"" } ?: "null"},\n")
                    // The whole screen is the "keyboard" here: the cut looks for the difference in all of it.
                    append("  \"keyboardTop\": 0,\n")
                    append("  \"keyboardHeight\": 0,\n")
                    append("  \"durations\": []\n")
                    append("}\n")
                },
            )
        }
        resetFakes()
    }

    /** Until the tree stops changing: the screen fills in from DataStore and its entrance animation. */
    private fun settle(first: Boolean = false) {
        val all = SemanticsMatcher("any") { true }
        val deadline = System.currentTimeMillis() + if (first) 30_000 else 10_000
        var last = -1
        var stable = 0
        while (stable < 3 && System.currentTimeMillis() < deadline) {
            compose.mainClock.advanceTimeBy(100)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            val count = runCatching { compose.onAllNodes(all).fetchSemanticsNodes().size }.getOrDefault(0)
            if (count > 3 && count == last) stable++ else stable = 0
            last = count
            Thread.sleep(120)
        }
    }
}

/** One picture of a settings screen: the route it opens and the state it opens in. */
class HelpScreenFrame(val label: String?, val route: String, val seed: suspend Seed.() -> Unit = {})

/** A settings-app help visual for the setting whose title resource is named [key]. */
class HelpScreen(val key: String, val kind: String, val frames: List<HelpScreenFrame>) {
    override fun toString(): String = key
}

/** [route] with [set] called with false and with true. */
private fun screenToggle(
    key: String,
    route: String,
    base: suspend Seed.() -> Unit = {},
    set: suspend SettingsRepository.(Boolean) -> Unit,
) = HelpScreen(
    key, HelpShot.COMPARE,
    listOf(
        HelpScreenFrame("common_off", route) { base(); repo.set(false) },
        HelpScreenFrame("common_on", route) { base(); repo.set(true) },
    ),
)

val HELP_SCREENS: List<HelpScreen> = listOf(
    screenToggle("accessibility_row_icons_title", "typing") { setSettingsRowIcons(it) },
    screenToggle("favourites_button_title", "typing") { setFavouritesButton(it) },
    screenToggle("toolai_hub_chat_button_everywhere_title", "typing") { setAiChatButtonEverywhere(it) },
    screenToggle("accessibility_keyboard_preview_title", "typing") { setKeyboardPreviewButton(it) },
    screenToggle("settings_switched_off_rows_title", "typing/gestures/glide") { setSwitchedOffRows(it) },
    screenToggle("settings_hidden_rows_title", "typing", base = { repo.setSettingsDepth(SettingsDepth.SIMPLE) }) { setHiddenSettingsRows(it) },
    HelpScreen(
        "settings_depth_title", HelpShot.GALLERY,
        listOf(
            HelpScreenFrame("settings_depth_simple", "typing") { repo.setSettingsDepth(SettingsDepth.SIMPLE) },
            HelpScreenFrame("settings_depth_standard", "typing") { repo.setSettingsDepth(SettingsDepth.STANDARD) },
            HelpScreenFrame("settings_depth_everything", "typing") { repo.setSettingsDepth(SettingsDepth.EVERYTHING) },
        ),
    ),
    HelpScreen(
        "about_setting_links_title", HelpShot.GALLERY,
        listOf(
            HelpScreenFrame("common_off", "accessibility") { repo.setSettingLinks(SettingLinkMode.OFF) },
            HelpScreenFrame("about_setting_links_button", "accessibility") { repo.setSettingLinks(SettingLinkMode.BUTTON) },
        ),
    ),
)
