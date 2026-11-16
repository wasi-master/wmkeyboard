package com.wasimaster.wmkeyboard.docshots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The raw frames of the help pictures: what a setting's "?" dialog shows above
 * its words. Rendered on the JVM by the same harness as the docs screenshots
 * ([withKeyboard]), each in light and dark.
 *
 *     ./gradlew :app:testEnDebugUnitTest -Pwmkb.docShots=true --tests "*HelpShots*" \
 *         -Pwmkb.helpShots.only='<regex over title resource names, .* for all>'
 *     python3 tools/helpvisuals/build.py
 *
 * Each frame of each visual is one test, so a frame that fails costs that
 * frame. A frame is a whole [KbShot]: the keyboard booted fresh on its own
 * seed, driven by its own steps. The off and on halves of a comparison are two
 * boots that differ in one setting, never one boot with the setting flipped,
 * so each half shows what a user with that setting would really see.
 *
 * This writes screens, not pictures. For every frame it leaves
 * `<key>/<light|dark>/<n>.png` (or `<n>.<frame>.png` for a clip) and a
 * `<n>.json` saying where the keyboard sits and how the visual wants to be
 * cut; tools/helpvisuals/build.py does the cutting, the scaling, the WebP and
 * the index the app ships. Nothing runs unless `wmkb.helpShots.only` is set,
 * so a docs screenshot run does not render these as well.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h851dp-normal-long-notround-any-440dpi-keyshidden-nonav")
class HelpShots(
    private val visual: HelpShot,
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
            val keys = HashSet<String>()
            for (shot in HELP_SHOTS + HELP_SHOTS_2) require(keys.add(shot.key)) { "two help visuals for ${shot.key}" }
            return (HELP_SHOTS + HELP_SHOTS_2)
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
        val dir = File(out, "${visual.key}/${if (dark) "dark" else "light"}")
        dir.mkdirs()
        // A frame from an earlier run must not outlive a clip that got shorter.
        dir.listFiles { file -> file.name.startsWith("$index.") }?.forEach { it.delete() }
        val frame = visual.frames[index]
        val base = File(dir, "$index")
        val durations = ArrayList<Long>()
        withKeyboard(
            compose, frame.shot, dark, base,
            prepare = { stage ->
                stage.steps.onFrame = { millis ->
                    captureScreenRoboImage(File(dir, "$index.%03d.png".format(durations.size)).path)
                    durations += millis
                }
            },
        ) { stage ->
            if (visual.kind != HelpShot.CLIP) captureScreenRoboImage("${base.path}.png")
            val top = IntArray(2).also { stage.keyboard.getLocationInWindow(it) }[1]
            base.resolveSibling("$index.json").writeText(
                buildString {
                    append("{\n")
                    append("  \"key\": \"${visual.key}\",\n")
                    append("  \"kind\": \"${visual.kind}\",\n")
                    append("  \"crop\": \"${visual.crop.id}\",\n")
                    append("  \"above\": ${visual.aboveDp},\n")
                    append("  \"frames\": ${visual.frames.size},\n")
                    append("  \"label\": ${frame.label?.let { "\"$it\"" } ?: "null"},\n")
                    append("  \"keyboardTop\": $top,\n")
                    append("  \"keyboardHeight\": ${stage.keyboard.height},\n")
                    append("  \"durations\": [${durations.joinToString(", ")}]\n")
                    append("}\n")
                },
            )
        }
    }
}

/** How a help picture is cut out of the screen it was taken from; build.py does the cutting. */
enum class HelpCrop(val id: String) {
    /** The keyboard, edge to edge, and nothing above it. */
    KEYBOARD("keyboard"),

    /**
     * The part of the keyboard that differs between the frames, moved out to
     * whole rows of keys. What an off/on comparison wants: the reader's eye is
     * put on the change. Where only the text in the field differs, the cut is
     * [TEXT]'s.
     */
    DIFF("diff"),

    /**
     * The line of the field that differs, set right over the part of the
     * keyboard that does: for a setting whose effect lands in the text.
     */
    TEXT("text"),

    /** The whole screen. */
    SCREEN("screen"),
}

/** One picture of a visual: the label it is shown under, and the keyboard state it is a picture of. */
class HelpFrameSpec(val label: String?, val shot: KbShot)

/**
 * The picture, clip or comparison for the setting whose title resource is
 * named [key]. Built with [still], [compare], [toggle], [gallery] or [clip].
 */
class HelpShot(
    val key: String,
    val kind: String,
    val frames: List<HelpFrameSpec>,
    val crop: HelpCrop,
    /** Extra screen above the keyboard to keep, in dp: a popup or the host's field. */
    val aboveDp: Int = 0,
) {
    override fun toString(): String = key

    companion object {
        const val STILL = "still"
        const val CLIP = "clip"
        const val COMPARE = "compare"
        const val GALLERY = "gallery"
    }
}

/** A frame's own seed on top of [this] shot's. */
private fun KbShot.seeded(extra: suspend Seed.() -> Unit): KbShot {
    val own = seed
    return copy(seed = {
        own()
        extra()
    })
}

/** [this] shot with [extra] run after its own seed: one option of a gallery on a docs shot's state. */
internal fun KbShot.seededWith(extra: suspend Seed.() -> Unit): KbShot = seeded(extra)

/** The docs screenshot [id], as a help frame: one keyboard state, drawn once for both. */
internal fun docs(id: String): KbShot = KB_SHOTS.first { it.id == id }

/** One picture of the keyboard in the state [shot] sets up. */
internal fun still(
    key: String,
    shot: KbShot = KbShot(""),
    crop: HelpCrop = HelpCrop.KEYBOARD,
    aboveDp: Int = 0,
) = HelpShot(key, HelpShot.STILL, listOf(HelpFrameSpec(null, shot)), crop, aboveDp)

/**
 * [base] with [off] applied beside [base] with [on] applied, cut to what
 * differs. The labels are "Off" and "On" unless [labels] names two string
 * resources, or two literal words behind a `=` ("=40%").
 */
internal fun compare(
    key: String,
    base: KbShot = KbShot(""),
    off: suspend Seed.() -> Unit,
    on: suspend Seed.() -> Unit,
    crop: HelpCrop = HelpCrop.DIFF,
    labels: Pair<String, String> = "common_off" to "common_on",
    aboveDp: Int = 0,
) = HelpShot(
    key, HelpShot.COMPARE,
    listOf(HelpFrameSpec(labels.first, base.seeded(off)), HelpFrameSpec(labels.second, base.seeded(on))),
    crop, aboveDp,
)

/** [compare] for a switch: [set] is the repository's setter, called with false and with true. */
internal fun toggle(
    key: String,
    base: KbShot = KbShot(""),
    crop: HelpCrop = HelpCrop.DIFF,
    aboveDp: Int = 0,
    set: suspend SettingsRepository.(Boolean) -> Unit,
) = compare(key, base, off = { repo.set(false) }, on = { repo.set(true) }, crop = crop, aboveDp = aboveDp)

/** One picture per option, each under the option's own name ([options] pairs a label with its state). */
internal fun gallery(
    key: String,
    vararg options: Pair<String, KbShot>,
    crop: HelpCrop = HelpCrop.KEYBOARD,
    aboveDp: Int = 0,
) = HelpShot(key, HelpShot.GALLERY, options.map { HelpFrameSpec(it.first, it.second) }, crop, aboveDp)

/** A few seconds of the keyboard in use: whatever [shot]'s steps do inside `record { }`. */
internal fun clip(
    key: String,
    shot: KbShot,
    crop: HelpCrop = HelpCrop.KEYBOARD,
    aboveDp: Int = 0,
) = HelpShot(key, HelpShot.CLIP, listOf(HelpFrameSpec(null, shot)), crop, aboveDp)

/** A plain field with [steps] run on the keyboard under it. */
internal fun typed(steps: KbSteps.() -> Unit) = KbShot("", steps = steps)
