package com.wasimaster.wmkeyboard.docshots

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.FrameLayout
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.wasimaster.wmkeyboard.app.DocsShotActivity
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.WMKeyboardService
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Docs screenshots of the keyboard itself, rendered on the JVM, each in light
 * and dark.
 *
 * The same host the device shots used, DocsShotActivity, opens with the
 * extras a shot's manifest entry names, and its field is focused. The real
 * [WMKeyboardService] is then created, handed that field's own
 * InputConnection, and started on it; its input view is docked along the
 * bottom of the activity's window, where the IME window would sit. So what
 * is captured is the shipped keyboard reading the shipped settings, typing
 * into a real field: only the window it sits in is stood in for, because
 * Robolectric has no input-method window to show.
 *
 * Runs under `-Pwmkb.docShots=true` beside [SettingsShots], and writes to the
 * same place for docs/screenshots/finish.py.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h851dp-normal-long-notround-any-440dpi-keyshidden-nonav")
class KeyboardShots(
    private val shot: KbShot,
    private val dark: Boolean,
    @Suppress("unused") private val name: String,
) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{2}")
        fun shots(): List<Array<Any>> {
            val only = System.getProperty("wmkb.docShots.only")?.takeIf { it.isNotBlank() }?.toRegex()
            val modes = shotModes()
            return KB_SHOTS
                .filter { only == null || only.containsMatchIn(it.id) }
                .flatMap { shot ->
                    modes.map { dark ->
                        arrayOf(shot, dark, "${shot.id} ${if (dark) "dark" else "light"}")
                    }
                }
        }
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = File(System.getProperty("wmkb.docShots.out") ?: "build/docshots")

    @Test
    fun capture() {
        val base = File(out, "${shot.id}.${if (dark) "dark" else "light"}")
        withKeyboard(compose, shot, dark, base) {
            captureScreenRoboImage("${base.path}.png")
            File("${base.path}.json").writeText("{}\n")
        }
    }
}

/** The keyboard as [withKeyboard] set it up: the steps that drive it, and its docked view. */
internal class KbStage(val steps: KbStepsImpl, val keyboard: View)

/**
 * Opens the host for [shot] in light or [dark], boots the real keyboard on its
 * field, runs the shot's seed and steps, and hands the result to [body] to
 * capture. [prepare] runs once the keyboard is up and before the steps, for a
 * caller that records them. A failure leaves `<failBase>.fail.png` and
 * `.fail.txt` behind and is thrown on.
 *
 * Shared by the docs screenshots ([KeyboardShots]) and the help pictures
 * ([HelpShots]), so both draw the same keyboard the same way.
 */
internal fun withKeyboard(
    compose: ComposeTestRule,
    shot: KbShot,
    dark: Boolean,
    failBase: File,
    prepare: (KbStage) -> Unit = {},
    body: (KbStage) -> Unit,
) {
    val app = ApplicationProvider.getApplicationContext<Application>()
    grantInternet(app)
    // A hardware keyboard is a configuration, the way Android reports one.
    RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
    if (shot.hardwareKeyboard) RuntimeEnvironment.setQualifiers("+keysexposed-qwerty")
    androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(8, TimeUnit.SECONDS)
    androidx.test.espresso.IdlingPolicies.setIdlingResourceTimeout(8, TimeUnit.SECONDS)
    val repo = SettingsRepository(app)
    runBlocking {
        repo.clearAllPreferences()
        repo.setOnboardingDone(true)
        shot.seed(Seed(repo, app))
    }
    val intent = Intent(app, DocsShotActivity::class.java)
        .putExtra("mode", shot.mode)
        .putExtra("kind", shot.kind)
        .putExtra("action", shot.action)
        .putExtra("theme", if (dark) "dark" else "light")
    shot.hint?.let { intent.putExtra("hint", it) }
    shot.text?.let { intent.putExtra("text", it) }

    ActivityScenario.launch<DocsShotActivity>(intent).use { scenario ->
        val steps = KbStepsImpl(compose)
        failBase.parentFile?.mkdirs()
        var controller: org.robolectric.android.controller.ServiceController<ShotKeyboard>? = null
        val stage: KbStage
        try {
            steps.settle(first = true)
            val (field, info) = focusField(compose, scenario)
            // The focused field's cursor blinks forever, so Compose never
            // reads as idle and every wait would run to its timeout. From
            // here time moves only when a step moves it (see tick).
            compose.mainClock.autoAdvance = false
            val ic = field?.onCreateInputConnection(info)
            controller = Robolectric.buildService(ShotKeyboard::class.java)
            val service = controller.create().get()
            service.ic = ic
            service.info = info
            steps.service = service
            service.onStartInput(info, false)
            val view = service.onCreateInputView()
            service.onStartInputView(info, false)
            scenario.onActivity { activity -> dock(activity, view) }
            steps.settle(first = true)
            stage = KbStage(steps, view)
            prepare(stage)
            shot.steps(steps)
            steps.settle()
        } catch (failure: Throwable) {
            runCatching { captureScreenRoboImage("${failBase.path}.fail.png") }
            File("${failBase.path}.fail.txt").writeText("$failure\n" + failure.stackTrace.take(20).joinToString("\n"))
            throw failure
        }
        body(stage)
        runCatching { controller?.destroy() }
    }
    resetFakes()
}

/**
 * Focuses the host's field the way a tap would, and returns the view
 * holding focus with the EditorInfo it fills in: the field's own input
 * type, action and selection.
 */
private fun focusField(compose: ComposeTestRule, scenario: ActivityScenario<DocsShotActivity>): Pair<View?, EditorInfo> {
    runCatching { compose.onAllNodes(hasSetTextAction()).onFirst().performClick() }
    var focused: View? = null
    scenario.onActivity { activity ->
        focused = activity.window.decorView.findFocus()
        if (focused == null) {
            // The chat host's field is a platform EditText, which no Compose
            // matcher reaches.
            firstEditText(activity.window.decorView)?.let {
                it.requestFocus()
                focused = it
            }
        }
    }
    compose.waitForIdle()
    val info = EditorInfo().apply { packageName = "com.example.messages" }
    return focused to info
}

private fun firstEditText(view: View): EditText? = when (view) {
    is EditText -> view
    is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { firstEditText(view.getChildAt(it)) }
    else -> null
}

private fun dock(activity: Activity, view: View) {
    (view.parent as? ViewGroup)?.removeView(view)
    (activity.window.decorView as ViewGroup).addView(
        view,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ),
    )
}

/** The service, typing into the host's field rather than an IME binding's. */
class ShotKeyboard : WMKeyboardService() {
    var ic: InputConnection? = null
    var info: EditorInfo? = null

    override fun getCurrentInputConnection(): InputConnection? = ic
    override fun getCurrentInputEditorInfo(): EditorInfo? = info ?: super.getCurrentInputEditorInfo()

    // The window is shown on a phone; here nothing calls showWindow(), and the
    // service would read every hardware key as arriving with its view hidden.
    override fun isInputViewShown(): Boolean = true
}

/** One keyboard docs screenshot. [id] is the manifest id. */
data class KbShot(
    val id: String,
    /** DocsShotActivity's extras: `field`, `chat` or `blank`. */
    val mode: String = "field",
    val kind: String = "text",
    val action: String = "none",
    val hint: String? = null,
    val text: String? = null,
    val seed: suspend Seed.() -> Unit = {},
    val steps: KbSteps.() -> Unit = {},
    /** A physical keyboard is attached: Android's `qwerty` configuration. */
    val hardwareKeyboard: Boolean = false,
)

interface KbSteps {
    val rule: ComposeTestRule
    val service: WMKeyboardService

    /** Types [text] key by key, the way fingers would: space, and Enter for a newline. */
    fun type(text: String)

    /** Presses one key. */
    fun key(key: Key)

    fun panel(panel: PanelMode)

    fun tool(tool: ToolbarTool)

    /** Holds the key showing [label] down, and leaves it held for the capture. */
    fun hold(label: String, millis: Long = 700)

    /** Taps the node showing [text] (or described as it). */
    fun tap(text: String, substring: Boolean = false)

    /** Puts [text] on the system clipboard, the way copying it in another app would. */
    fun copy(text: String)

    /**
     * Presses the key showing [label], holds it [holdMillis], then drags it
     * [dx] px sideways and [dy] down, and leaves it held for the capture.
     */
    fun drag(label: String, dx: Float, dy: Float = 0f, holdMillis: Long = 0)

    /** Holds the node described as [description] (a grid tile with no text). */
    fun holdDescription(description: String, millis: Long = 700)

    /** Selects the first occurrence of [text] in the field, as a long-press would. */
    fun select(text: String)

    /** Presses and releases a key on a physical keyboard. */
    fun hardwareKey(keyCode: Int, meta: Int = 0)

    /** One reading from the sensor of [type], handed to whoever listens. */
    fun sensorEvent(type: Int, vararg values: Float)

    /** Drives the speech recogniser the service created last. */
    fun speech(event: org.robolectric.shadows.ShadowSpeechRecognizer.() -> Unit)

    /** Lets [millis] of wall time pass: for work on real background threads. */
    fun pause(millis: Long)

    fun settle()

    /**
     * Draws [word] as one glide stroke, key centre to key centre, the finger
     * taking [perKeyMillis] between letters, then lifts. With [lift] off the
     * finger stays down on the last letter, the trail and the preview still
     * showing, for a picture of a glide under way.
     */
    fun glide(word: String, perKeyMillis: Long = 180, lift: Boolean = true)

    /**
     * Everything [block] does is filmed, a frame each time the clock moves:
     * the frames of a help clip ([HelpShots]). The docs screenshots have no
     * film to put them in, and run [block] as it is.
     */
    fun record(block: () -> Unit)

    /** Holds still for [millis] of the keyboard's own clock: a beat in a clip. */
    fun hold(millis: Long)
}

internal class KbStepsImpl(override val rule: ComposeTestRule) : KbSteps {
    override lateinit var service: WMKeyboardService

    /** Takes one frame of a clip, shown for the milliseconds it is handed; set by whoever is filming. */
    var onFrame: ((Long) -> Unit)? = null
    private var recording = false

    private val all = SemanticsMatcher("any") { true }

    fun settle(first: Boolean = false) {
        val deadline = System.currentTimeMillis() + if (first) 20_000 else 8_000
        var last = -1
        var stable = 0
        while (stable < 4 && System.currentTimeMillis() < deadline) {
            tick()
            val count = runCatching { rule.onAllNodes(all).fetchSemanticsNodes().size }.getOrDefault(0)
            if (count > 3 && count == last) stable++ else stable = 0
            last = count
            Thread.sleep(120)
        }
    }

    /**
     * Moves both clocks on: Compose's, for the keyboard's entrance and its
     * toolbar, and the main looper's, which the service's coroutines and the
     * suggestion work posted back from its background threads run on.
     */
    private fun tick() = step(100)

    /** [tick] of any length, filmed while [record] is running. */
    private fun step(millis: Long) {
        rule.mainClock.advanceTimeBy(millis)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        if (recording) onFrame?.invoke(millis)
    }

    override fun record(block: () -> Unit) {
        recording = true
        try {
            block()
        } finally {
            recording = false
        }
    }

    override fun hold(millis: Long) {
        var left = millis
        while (left > 0) {
            step(minOf(left, 100))
            left -= 100
        }
    }

    override fun glide(word: String, perKeyMillis: Long, lift: Boolean) {
        val keys = word.map { keyNode(it.toString()) }
        val first = keys.first()
        // Touch input is given in the first key's own coordinates, so every
        // other key is placed relative to that key's corner.
        val origin = first.fetchSemanticsNode().boundsInRoot.topLeft
        val centers = keys.map { it.fetchSemanticsNode().boundsInRoot.center - origin }
        first.performTouchInput { down(centers[0]) }
        step(GLIDE_STEP_MS)
        val pieces = (perKeyMillis / GLIDE_STEP_MS).toInt().coerceAtLeast(2)
        for (i in 1 until centers.size) {
            val from = centers[i - 1]
            val to = centers[i]
            for (piece in 1..pieces) {
                val at = from + (to - from) * (piece.toFloat() / pieces)
                first.performTouchInput { moveTo(at) }
                step(GLIDE_STEP_MS)
            }
        }
        step(GLIDE_STEP_MS)
        if (!lift) {
            // The trail fades behind a finger that stops, so the capture is
            // taken a few frames after the last move, no later.
            repeat(2) { step(GLIDE_STEP_MS) }
            return
        }
        first.performTouchInput { up() }
        settle()
    }

    override fun settle() = settle(first = false)

    override fun type(text: String) {
        for (c in text) {
            key(
                when (c) {
                    ' ' -> Key(label = " ", action = KeyAction.Space)
                    '\n' -> Key(label = "", action = KeyAction.Enter)
                    else -> Key(label = c.toString())
                },
            )
        }
        settle()
    }

    override fun key(key: Key) {
        service.onKey(key)
        tick()
    }

    override fun panel(panel: PanelMode) {
        service.onPanelChange(panel)
        settle()
    }

    override fun tool(tool: ToolbarTool) {
        service.onToolTap(tool)
        settle()
    }

    /**
     * The key showing [label]. A letter key shows its capital while shift is
     * on, as it is at the start of an empty field, so a lone letter is matched
     * in either case.
     */
    private fun keyNode(label: String) =
        rule.onAllNodesWithText(label, ignoreCase = label.length == 1).onFirst()

    override fun hold(label: String, millis: Long) {
        val node = keyNode(label)
        node.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(millis)
        repeat(6) { tick() }
    }

    override fun copy(text: String) {
        val clipboard = service.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", text))
        settle()
    }

    private fun byTextOrDescription(label: String, substring: Boolean = true) =
        rule.onAllNodesWithText(label, substring = substring).let { byText ->
            if (byText.fetchSemanticsNodes().isNotEmpty()) byText.onFirst()
            else rule.onAllNodesWithContentDescription(label, substring = substring).onFirst()
        }

    override fun drag(label: String, dx: Float, dy: Float, holdMillis: Long) {
        val node = byTextOrDescription(label)
        node.performTouchInput { down(center) }
        if (holdMillis > 0) {
            rule.mainClock.advanceTimeBy(holdMillis)
            repeat(3) { tick() }
        }
        node.performTouchInput {
            repeat(8) { moveBy(androidx.compose.ui.geometry.Offset(dx / 8, dy / 8)) }
        }
        repeat(6) { tick() }
    }

    override fun holdDescription(description: String, millis: Long) {
        val node = rule.onAllNodesWithContentDescription(description, substring = true).onFirst()
        node.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(millis)
        repeat(6) { tick() }
    }

    override fun select(text: String) {
        val ic = requireNotNull(service.currentInputConnection) { "no field focused" }
        val before = ic.getTextBeforeCursor(4000, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(4000, 0)?.toString().orEmpty()
        val start = (before + after).indexOf(text)
        require(start >= 0) { "\"$text\" is not in the field" }
        ic.setSelection(start, start + text.length)
        // No input-method manager relays the change here, so hand it over the
        // way the platform would.
        service.onUpdateSelection(before.length, before.length, start, start + text.length, -1, -1)
        settle()
    }

    override fun hardwareKey(keyCode: Int, meta: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        val down = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0, meta)
        val up = android.view.KeyEvent(now, now + 30, android.view.KeyEvent.ACTION_UP, keyCode, 0, meta)
        service.onKeyDown(keyCode, down)
        service.onKeyUp(keyCode, up)
        tick()
    }

    override fun sensorEvent(type: Int, vararg values: Float) {
        val manager = service.getSystemService(android.hardware.SensorManager::class.java)
        val sensor = manager.getDefaultSensor(type) ?: error("no sensor of type $type was seeded")
        val event = org.robolectric.shadows.SensorEventBuilder.newBuilder()
            .setSensor(sensor)
            .setValues(values)
            .setTimestamp(android.os.SystemClock.elapsedRealtimeNanos())
            .build()
        shadowOf(manager).sendSensorEventToListeners(event)
        repeat(4) { tick() }
    }

    override fun speech(event: org.robolectric.shadows.ShadowSpeechRecognizer.() -> Unit) {
        val recognizer = org.robolectric.shadows.ShadowSpeechRecognizer.getLatestSpeechRecognizer()
            ?: error("the service has not created a speech recogniser")
        shadowOf(recognizer).event()
        repeat(4) { tick() }
    }

    override fun pause(millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            tick()
            Thread.sleep(50)
        }
    }

    override fun tap(text: String, substring: Boolean) {
        val byText = rule.onAllNodesWithText(text, substring = substring)
        val node = if (byText.fetchSemanticsNodes().isNotEmpty()) byText.onFirst()
        else rule.onAllNodesWithContentDescription(text, substring = substring).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
        settle()
    }
}

/** A glide stroke moves, and is filmed, this often: 25 frames a second. */
private const val GLIDE_STEP_MS = 40L

/** Every keyboard shot, by manifest id. */
val KB_SHOTS: List<KbShot> = listOf(
    // ------------------------------------------------------------ the board at rest
    KbShot("tour/anatomy-default"),
    KbShot("tour/toolbar-pinned"),
    KbShot("languages/spacebar-resting-label"),
    KbShot("typing/number-row", seed = { repo.setNumberRow(true) }),
    KbShot("accessibility/high-contrast-live", seed = { repo.setHighContrastKeys(true) }),
    KbShot("accessibility/color-vision-live", seed = {
        repo.setKeyboardThemeId(theme("aurora").id)
        repo.setColorVisionFilter(com.wasimaster.wmkeyboard.core.settings.ColorVisionFilter.DEUTERANOPIA)
    }),
    KbShot("themes/amoled-default-theme", seed = { repo.setThemeMode(com.wasimaster.wmkeyboard.core.settings.ThemeMode.AMOLED) }),
    KbShot("emoji/emoji-row-own", seed = { repo.setEmojiBarMode(com.wasimaster.wmkeyboard.core.settings.EmojiBarMode.ALWAYS) }),
    KbShot("typing/symbol-row-picker", seed = { repo.setSymbolRowEnabled(true) }, steps = { tap("Switch the symbol set") }),
    KbShot("typing/size-position-one-handed", hint = "Message", seed = {
        repo.setOneHandedMode(com.wasimaster.wmkeyboard.core.settings.OneHandedMode.RIGHT)
    }, steps = { type("See you at six") }),
    KbShot("typing/size-position-split", mode = "blank", seed = { repo.setSplitKeyboard(true) }),
    KbShot("typing/size-position-floating", mode = "blank", seed = { repo.setFloatingKeyboard(false, true) }),

    // ------------------------------------------------------------ fields adapt
    KbShot("typing/field-adaptation-email", kind = "email", hint = "Email address"),
    KbShot("typing/field-adaptation-uri", kind = "uri", hint = "Website"),
    KbShot("typing/field-adaptation-numpad", kind = "phone", hint = "Phone number"),
    KbShot("typing/field-adaptation-password", kind = "password", hint = "Password", steps = { type("hunter22") }),
    KbShot("privacy/secure-field-no-suggestions", kind = "password"),
    KbShot("typing/enter-action-icons", action = "search", hint = "Search"),

    // ------------------------------------------------------------ keys and layers
    KbShot("typing/symbols-page-open", steps = { key(Key(label = "?123", action = KeyAction.Symbols)); settle() }),
    KbShot("typing/long-press-popup", steps = { hold("e") }),
    KbShot("tour/long-press-alternates", steps = { hold("a") }),
    KbShot("languages/spacebar-language-preview", steps = { drag("English", 180f) }),
    KbShot("languages/hold-drag-picker-list", seed = {
        repo.setEnabledLayoutIds(listOf("builtin_qwerty", "builtin_avro", "builtin_probhat", "builtin_jatiya", "asset_fancy"))
    }, steps = { drag("English", 0f, -120f, holdMillis = 500) }),

    // ------------------------------------------------------------ the strip
    KbShot("smart/suggestion-strip-anatomy", steps = { type("cons") }),
    KbShot("smart/suggestion-strip-emoji-tail", steps = { type("birthday") }),
    KbShot("emoji/word-suggestions", steps = { type("birthday") }),
    KbShot("smart/suggestion-strip-ambiguous-correction", steps = { type("helo") }),
    // "hello" has emoji of its own, and emoji take the strip's tail before punctuation.
    KbShot("smart/punctuation-chips", seed = { repo.setPunctuationSuggestions(true) }, steps = { type("testing") }),
    KbShot("smart/suggestion-strip-shift-recase", steps = { type("world"); key(Key(label = "", action = KeyAction.Shift)); settle() }),
    KbShot("start/migrating-suggestion-center", steps = { type("hte") }),
    KbShot("emoji/inline-colon-search", steps = { type(":cat") }),
    KbShot("smart/calc-chip", steps = { type("12*8") }),
    KbShot("smart/keyword-chip", steps = { type("wiki") }),
    KbShot("smart/autocorrect-confidence-fix", steps = { type("wprld wh") }),
    KbShot("smart/autocorrect-skip-allcaps", steps = { type("ASAP wprld ") }),
    KbShot("smart/autocorrect-backspace-undo", steps = { type("wprld "); key(Key(label = "", action = KeyAction.Delete)); settle() }),
    KbShot("smart/otp-fresh-copy-chip", steps = { copy("Your verification code is 483920") }),

    // ------------------------------------------------------------ languages
    KbShot("languages/probhat-key-grid", seed = { layout("builtin_probhat") }),
    KbShot("languages/avro-lenient-spelling", seed = { layout("builtin_avro") }, steps = { type("ami valo achi ") }),
    KbShot("languages/cjk-japanese-flick", seed = { layout("asset_ja_flick") }),
    KbShot("languages/fancy-text-bold-field", seed = { layout("asset_fancy") }, steps = { type("hello") }),
    KbShot("languages/notation-music-field", seed = { layout("asset_music") }),
    KbShot("languages/notation-braille-chord", seed = { layout("asset_braille_chord") }),

    // ------------------------------------------------------------ panels
    KbShot("tour/toolbox-grid", steps = { panel(PanelMode.TOOLBOX) }),
    KbShot("tools/toolbar-and-toolbox", steps = { panel(PanelMode.TOOLBOX) }),
    KbShot("start/editions-toolbox-full", steps = { panel(PanelMode.TOOLBOX) }),
    KbShot("smart/grammar-toolbox-entry", steps = { panel(PanelMode.TOOLBOX) }),
    KbShot("privacy/incognito-toolbar-badge", steps = { tool(ToolbarTool.INCOGNITO) }),
    KbShot("emoji/panel-overview", steps = { panel(PanelMode.EMOJI) }),
    KbShot("emoji/panel-search", steps = { panel(PanelMode.EMOJI); tap("Search", substring = true); type("cat") }),
    KbShot("emoji/kaomoji-tab", seed = { repo.setEmojiKaomojiTabs(true) }, steps = { panel(PanelMode.EMOJI); tap("^_^") }),
    KbShot("tools/clipboard-panel", steps = {
        copy("Pick up milk and eggs on the way home")
        copy("https://wmkeyboard.app/tools/clipboard/")
        copy("Your code is 482913. Call us at 555-201-4488 if you did not request this.")
        panel(PanelMode.CLIPBOARD)
    }),
    KbShot("tools/clipboard-entity-chips", steps = {
        copy("Your code is 482913. Call us at 555-201-4488 if you did not request this.")
        panel(PanelMode.CLIPBOARD)
    }),
    KbShot("smart/clipboard-entity-strip", steps = {
        copy("Your verification code is 483920")
        copy("call me at 555-123-4567")
        copy("see https://example.com/docs")
        panel(PanelMode.CLIPBOARD)
    }),
    KbShot("privacy/clipboard-sensitive-masked", steps = { copy("Groceries for Saturday"); copy("aB3!kZ9xQ7z"); panel(PanelMode.CLIPBOARD) }),
    KbShot(
        "tools/text-edit-panel", kind = "multiline",
        text = "The quick brown fox jumps over the lazy dog.\nA second line of sample text to edit.",
        steps = { panel(PanelMode.TEXT_EDIT) },
    ),
    KbShot("tools/numpad-panel", steps = { panel(PanelMode.NUMPAD) }),
    KbShot("tools/calculator-panel", steps = { panel(PanelMode.CALCULATOR) }),
    KbShot("tools/unit-converter-panel", steps = { panel(PanelMode.UNIT_CONVERT) }),
    KbShot("tools/symbols-panel", steps = { panel(PanelMode.SYMBOLS) }),
    KbShot("tools/qr-generator-panel", text = "https://wmkeyboard.app", steps = { panel(PanelMode.QR_GEN) }),
    KbShot("tools/password-generator-panel", steps = { panel(PanelMode.PASSWORD_GEN) }),
    KbShot("tools/text-repeat-panel", text = "ha", steps = { panel(PanelMode.TEXT_REPEAT) }),
    KbShot("tools/typing-test-panel", steps = { panel(PanelMode.TYPING_TEST) }),
    KbShot("typing/modes-tool-panel", steps = { panel(PanelMode.MODES) }),
    KbShot("typing/modes-manual-picker", steps = { panel(PanelMode.MODES) }),
    KbShot("reference/troubleshooting-panel-captures-input", steps = { panel(PanelMode.SNIPPETS) }),
    KbShot("typing/sound-haptics-toolbar-panel", steps = { panel(PanelMode.SOUND_HAPTICS) }),
    KbShot("tools/moon-phase-panel", mode = "blank", steps = { panel(PanelMode.MOON_PHASE) }),
    KbShot("tools/media-access-prompt", mode = "blank", steps = { panel(PanelMode.MEDIA_CONTROL) }),
    KbShot("tools/camera-permission-gate", mode = "blank", steps = { panel(PanelMode.CAMERA) }),
    KbShot("tools/ai-need-setup", steps = { panel(PanelMode.AI) }),
    KbShot("tools/search-needs-key", steps = { panel(PanelMode.WEB_SEARCH) }),
    KbShot("tools/calendar-month-grid", seed = { grant(android.Manifest.permission.READ_CALENDAR) }, steps = { panel(PanelMode.CALENDAR) }),
) + MORE_KB_SHOTS

/** Makes [id] the only other layout beside QWERTY, and the one showing. */
internal suspend fun Seed.layout(id: String) {
    repo.setEnabledLayoutIds(listOf("builtin_qwerty", id))
    repo.setActiveLayoutId(id)
}

internal fun Seed.grant(permission: String) {
    shadowOf(context as Application).grantPermissions(permission)
}
