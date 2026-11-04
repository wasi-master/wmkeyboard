package com.wasimaster.wmkeyboard.docshots

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.DocsShotActivity
import com.wasimaster.wmkeyboard.app.describe
import com.wasimaster.wmkeyboard.app.describeImport
import com.wasimaster.wmkeyboard.app.describeSoundPackImport
import com.wasimaster.wmkeyboard.core.feedback.SoundPackFile
import com.wasimaster.wmkeyboard.core.feedback.SoundPackImportResult
import com.wasimaster.wmkeyboard.core.feedback.SoundPackStore
import com.wasimaster.wmkeyboard.core.icons.IconImportResult
import com.wasimaster.wmkeyboard.core.icons.IconPackFile
import com.wasimaster.wmkeyboard.core.icons.IconPackStore
import com.wasimaster.wmkeyboard.core.snippets.SnippetFile
import com.wasimaster.wmkeyboard.core.stickers.StickerImportResult
import com.wasimaster.wmkeyboard.core.stickers.StickerPackFile
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayoutFile
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.theme.ThemeAnimation
import com.wasimaster.wmkeyboard.core.theme.ThemeCodec
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.withExtractedImages
import com.wasimaster.wmkeyboard.core.theme.withFreshIds
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.layoutKind
import com.wasimaster.wmkeyboard.core.util.readCapped
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The community addon repository's full pass: one submitted addon checked by
 * the app's own importer, and, for a theme, layout or icon pack, drawn on the
 * real keyboard.
 *
 *   ./gradlew :app:testEnDebugUnitTest -Pwmkb.docShots=true \
 *     --tests 'com.wasimaster.wmkeyboard.docshots.AddonShots' \
 *     -Pwmkb.addonShots.in=<dir> -Pwmkb.addonShots.out=<dir>
 *
 * The in directory holds `request.json`, `{"type":…,"id":…,"file":…}` (type
 * `theme`, `layout`, `icon_pack`, `stickers`, `sound_pack` or `snippets`),
 * beside the file it names. The out directory gets `<id>.png` (a theme also
 * `<id>-tools.png`, `<id>-clipboard.png` and `<id>-editing.png`, the same
 * keyboard with a tool panel open, and `<id>-landscape.png` when it has a
 * landscape image; a layout `<id>-dark.png`, `<id>-shift.png`, and one more
 * per layer it defines, named in [layoutLayers]; an icon pack `<id>-tools.png`
 * too, its tool icons on the toolbox; sticker, sound and snippet packs are
 * only imported, and the bot keeps the web pass's pictures of stickers)
 * and always `<id>.report.json`, `{"ok","refusal","warnings","images"}`, which
 * the bot reads whatever happened: a refused file and a render that threw both
 * still write one.
 *
 * Validation is the importer itself, not a copy of its rules: a theme takes
 * the steps the Themes screen's Import button takes, a layout those of the
 * layout editor's. Whatever the app would refuse is refused here with the
 * message the app would show. The render is [KeyboardShots]' boot, the theme
 * worn or the layout showing, with a few words typed so the strip has
 * suggestions in it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h851dp-normal-long-notround-any-440dpi-keyshidden-nonav")
class AddonShots {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val input = File(System.getProperty("wmkb.addonShots.in").orEmpty())
    private val out = File(System.getProperty("wmkb.addonShots.out").orEmpty().ifBlank { "build/addonshots" })

    private val all = SemanticsMatcher("any") { true }

    @Test
    fun render() {
        // The docs run picks up every class in this package; with no request it has nothing to do.
        assumeTrue(input.path.isNotBlank())
        val app = ApplicationProvider.getApplicationContext<Application>()
        grantInternet(app)
        androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(8, TimeUnit.SECONDS)
        androidx.test.espresso.IdlingPolicies.setIdlingResourceTimeout(8, TimeUnit.SECONDS)
        out.mkdirs()

        val request = Json.parseToJsonElement(File(input, "request.json").readText()).jsonObject
        val type = request.getValue("type").jsonPrimitive.content
        val id = request.getValue("id").jsonPrimitive.content
        val file = File(input, request.getValue("file").jsonPrimitive.content)
        require(id.matches(Regex("[a-z0-9][a-z0-9-]*"))) { "id \"$id\" is not a slug" }

        var refusal: String? = null
        val warnings = mutableListOf<String>()
        val images = mutableListOf<String>()
        try {
            val repo = SettingsRepository(app)
            runBlocking {
                repo.clearAllPreferences()
                repo.setOnboardingDone(true)
                // The toolbox's first-open drag hint would take a third of the tools shot.
                repo.setToolboxHintDismissed(true)
            }
            when (type) {
                "theme" -> {
                    val theme = runBlocking { importTheme(app, repo, file) }
                    if (theme == null) {
                        refusal = app.getString(R.string.theme_import_wrong_file_error)
                    } else {
                        warnings += themeWarnings(theme)
                        // One boot, four captures: the typing shot, then the
                        // panels a theme also paints, each one opened in turn.
                        images += shoot("$id.png", dark = theme.dark, landscape = false, extras = themePanels(id))
                        if (theme.backgroundImageLandscape != null) {
                            images += shoot("$id-landscape.png", dark = theme.dark, landscape = true)
                        }
                    }
                }
                "layout" -> {
                    val imported = runBlocking { importLayout(app, repo, file) }
                    if (imported == null) {
                        refusal = app.getString(R.string.layout_editor_import_wrong_file_error)
                    } else {
                        val (layout, notes) = imported
                        warnings += notes
                        // Every layer the file defines, from the one light boot;
                        // the dark shot is the letters alone.
                        images += shoot("$id.png", dark = false, landscape = false, extras = layoutLayers(id, layout))
                        images += shoot("$id-dark.png", dark = true, landscape = false)
                    }
                }
                "icon_pack" -> {
                    // The Icons screen's Import, then the pack switched on, as it does.
                    val result = file.inputStream().use { IconPackFile.import(it, IconPackStore.get(app), "Icon pack") }
                    if (result is IconImportResult.Imported) {
                        warnings += result.repairs.map { "Repaired on import: " + it.resolve(app) }
                        runBlocking { repo.setIconPack(result.pack.id) }
                        // The toolbox is where most of a pack's icons are; the
                        // main shot has the strip and the keys' own.
                        images += shoot("$id.png", dark = false, landscape = false, extras = themePanels(id).take(1))
                    } else {
                        refusal = describeImport(app, result)
                    }
                }
                "stickers" -> {
                    val result = file.inputStream().use { StickerPackFile.import(it, StickerPackStore.get(app), "Stickers") }
                    if (result is StickerImportResult.Imported) {
                        warnings += result.repairs.map { "Repaired on import: " + it.resolve(app) }
                    } else {
                        refusal = result.describe(app)
                    }
                }
                "sound_pack" -> {
                    val result = file.inputStream().use { SoundPackFile.import(it, SoundPackStore.get(app), fallbackName = "Sounds") }
                    if (result !is SoundPackImportResult.Imported) refusal = describeSoundPackImport(app, result)
                }
                "snippets" -> {
                    val text = runCatching { file.inputStream().use { it.readCapped() } }.getOrNull()?.decodeToString()
                    val imported = text?.let { SnippetFile.decode(it) }
                    when {
                        imported == null -> refusal = app.getString(R.string.expander_import_error)
                        imported.snippets.isEmpty() -> refusal = "The file holds no snippet with any text to insert."
                        else -> warnings += imported.repairs.map { "Repaired on import: " + it.resolve(app) }
                    }
                }
                else -> refusal = "Unknown addon type \"$type\"."
            }
        } catch (failure: Throwable) {
            // The bot shows this as the reason; the stack goes beside it for whoever fixes the harness.
            refusal = refusal ?: "The render failed: $failure"
            File(out, "$id.fail.txt").writeText("$failure\n" + failure.stackTrace.take(30).joinToString("\n"))
        } finally {
            writeReport(id, refusal, warnings, images)
            resetFakes()
        }
    }

    /**
     * The Themes screen's Import, step for step: read under the import cap,
     * decode, remint the ids, write the base64 images out, store and wear it.
     * Null where that screen would say the file is not a theme.
     */
    private suspend fun importTheme(app: Application, repo: SettingsRepository, file: File): ThemeSpec? {
        val text = runCatching { file.inputStream().use { it.readCapped() } }.getOrNull()?.decodeToString()
        val parsed = text?.let { ThemeCodec.decode(it) } ?: return null
        val id = "custom_${System.currentTimeMillis()}"
        val stored = parsed.withFreshIds(id)
            .withExtractedImages(File(app.filesDir, "theme_images").apply { mkdirs() })
        repo.upsertCustomTheme(stored)
        repo.setKeyboardThemeId(id)
        return stored
    }

    /**
     * The layout editor's Import, then "Import and turn on", then the layout
     * picked. Returns the layout as stored, with the repair notes as the
     * editor would list them; null where it would say the file is not a layout.
     */
    private suspend fun importLayout(app: Application, repo: SettingsRepository, file: File): Pair<LayoutSpec, List<String>>? {
        val text = runCatching { file.inputStream().use { it.readCapped() } }.getOrNull()?.decodeToString()
        val imported = text?.let { LayoutFile.decode(it) } ?: return null
        val id = "custom_${System.currentTimeMillis()}"
        val layout = imported.layout.copy(id = id)
        repo.upsertCustomLayout(layout)
        repo.setEnabledLayoutIds(listOf("builtin_qwerty", id))
        repo.setActiveLayoutId(id)
        return layout to imported.repairNotes.map { "Repaired on import: " + it.format(app.resources) }
    }

    /**
     * One shot per layer [layout] defines, beyond the letters the main shot
     * already shows: the letters with Shift on, then each layer by the way a
     * user reaches it. Symbols, its second page and Fn by their keys; a page of
     * the layout's own by a page key; the number, phone, date and time pads by
     * a field of that kind; a panel layer by opening that panel. A layer the
     * layout leaves to the shipped one is not shown: it is not the submission.
     *
     * Each name is one word after the id, so the bot's preview names stay
     * `preview-<word>.png`. The layout's own pages are numbered, since their
     * keys are free text.
     */
    private fun layoutLayers(id: String, layout: LayoutSpec): List<Extra> = buildList {
        val keys = layout.layers.keys
        fun press(action: KeyAction): (ShotKeyboard, EditorInfo) -> Unit = { service, _ -> service.onKey(Key(label = "", action = action)) }
        add(Extra("$id-shift.png", press(KeyAction.Shift)))
        if (LayoutLayer.SYMBOLS.key in keys) add(Extra("$id-symbols.png", press(KeyAction.Symbols)))
        if (LayoutLayer.SYMBOLS_SHIFTED.key in keys) {
            add(Extra("$id-symbols2.png") { service, _ ->
                service.onKey(Key(label = "", action = KeyAction.Symbols))
                tick()
                service.onKey(Key(label = "", action = KeyAction.Symbols))
            })
        }
        if (LayoutLayer.FN.key in keys) add(Extra("$id-fn.png", press(KeyAction.Fn)))
        for ((layer, inputType) in PADS) {
            if (layer.key !in keys) continue
            add(Extra("$id-${layer.key}.png") { service, info ->
                val field = EditorInfo().apply {
                    packageName = info.packageName
                    this.inputType = inputType
                }
                service.info = field
                service.onStartInput(field, true)
                service.onStartInputView(field, true)
            })
        }
        val standard = LayoutLayer.entries.map { it.key }.toSet()
        keys.filter { it !in standard && !it.startsWith(PANEL_PREFIX) }.sorted().forEachIndexed { index, layer ->
            add(Extra("$id-page${index + 1}.png", press(KeyAction.LayerSwitch(layer))))
        }
        for (key in keys.filter { it.startsWith(PANEL_PREFIX) }.sorted()) {
            val kind = PanelKind.entries.firstOrNull { it.layerKey == key } ?: continue
            val panel = PanelMode.entries.firstOrNull { it.layoutKind == kind } ?: continue
            add(Extra("$id-${kind.name.lowercase().replace("_", "")}.png") { service, _ -> service.onPanelChange(panel) })
        }
    }

    /** What the runner draws with its defaults, since nothing is downloaded or installed here. */
    private fun themeWarnings(theme: ThemeSpec): List<String> = buildList {
        val fonts = listOfNotNull(theme.fontId) + theme.scriptFontIds.values
        for (font in fonts.distinct()) {
            when {
                font.startsWith("google:") ->
                    add("Font \"${font.removePrefix("google:")}\" is a Google font; the preview draws the default font in its place.")
                font.startsWith("installed:") ->
                    add("Font \"${font.removePrefix("installed:")}\" is an installed font addon; the preview draws the default font in its place.")
            }
        }
        if (theme.soundStyle == "CUSTOM" && !theme.soundCustomId.isNullOrBlank()) {
            add("Sound \"${theme.soundCustomId}\" is an installed sound addon; on a phone without it the global sound plays.")
        }
        if (theme.animation != ThemeAnimation.NONE) {
            add("The theme is animated (${theme.animation.name}); the preview is one still frame.")
        }
    }

    /**
     * The tool panels a theme is shown on besides the letters: the toolbox
     * grid (tool circles and their labels), the clipboard (chips and cards)
     * and text editing (modifier-coloured keys). Emoji are left out: the JVM
     * draws no emoji glyphs, so that panel would be a grid of blanks.
     */
    private fun themePanels(id: String): List<Extra> = listOf(
        Extra("$id-tools.png") { service, _ -> service.onPanelChange(PanelMode.TOOLBOX) },
        Extra("$id-clipboard.png") { service, _ -> service.onPanelChange(PanelMode.CLIPBOARD) },
        Extra("$id-editing.png") { service, _ -> service.onPanelChange(PanelMode.TEXT_EDIT) },
    )

    /** One more capture after the main one: [step] puts the keyboard in the state to draw. */
    private class Extra(val name: String, val step: (ShotKeyboard, EditorInfo) -> Unit)

    /**
     * One capture of the keyboard as it stands in settings, docked under the
     * host's field after a few typed words, the way [KeyboardShots.capture]
     * takes its shots; then one more per entry of [extras], each run from the
     * plain letters in the text field. Returns the names written, in order. An
     * extra that throws is left out rather than failing the shots before it.
     */
    private fun shoot(
        name: String,
        dark: Boolean,
        landscape: Boolean,
        extras: List<Extra> = emptyList(),
    ): List<String> {
        val written = mutableListOf<String>()
        val app = ApplicationProvider.getApplicationContext<Application>()
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        RuntimeEnvironment.setQualifiers(if (landscape) "+land" else "+port")
        compose.mainClock.autoAdvance = true
        val intent = Intent(app, DocsShotActivity::class.java)
            .putExtra("mode", "field")
            .putExtra("kind", "text")
            .putExtra("action", "none")
            .putExtra("hint", "Message")
            .putExtra("theme", if (dark) "dark" else "light")
        ActivityScenario.launch<DocsShotActivity>(intent).use { scenario ->
            val controller = Robolectric.buildService(ShotKeyboard::class.java)
            try {
                settle(first = true)
                runCatching { compose.onAllNodes(hasSetTextAction()).onFirst().performClick() }
                var field: android.view.View? = null
                scenario.onActivity { field = it.window.decorView.findFocus() }
                compose.waitForIdle()
                val info = EditorInfo().apply { packageName = "com.example.messages" }
                // The focused field's cursor blinks forever; time moves only on a tick from here.
                compose.mainClock.autoAdvance = false
                val service = controller.create().get()
                service.ic = field?.onCreateInputConnection(info)
                service.info = info
                service.onStartInput(info, false)
                val view = service.onCreateInputView()
                service.onStartInputView(info, false)
                scenario.onActivity { activity ->
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
                settle(first = true)
                for (c in PHRASE) {
                    service.onKey(if (c == ' ') Key(label = " ", action = KeyAction.Space) else Key(label = c.toString()))
                    tick()
                }
                settle()
                captureScreenRoboImage(File(out, name).path)
                written += name
                if (extras.isNotEmpty()) {
                    // Clips for the clipboard panel to show, copied while the
                    // keyboard is up so its history listener records them.
                    val clipboard = service.getSystemService(android.content.ClipboardManager::class.java)
                    for (clip in CLIPS) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", clip))
                        settle()
                    }
                }
                for (extra in extras) {
                    runCatching {
                        // Back to the letters in the text field: no panel, no
                        // layer, no pad left over from the extra before.
                        service.onPanelChange(PanelMode.NONE)
                        service.info = info
                        service.onStartInput(info, true)
                        service.onStartInputView(info, true)
                        settle()
                        // Clear of the double-tap window, so a Shift press is
                        // Shift and not caps lock off the last key.
                        repeat(10) { tick() }
                        extra.step(service, info)
                        settle()
                        captureScreenRoboImage(File(out, extra.name).path)
                    }.onSuccess { written += extra.name }
                }
            } finally {
                runCatching { controller.destroy() }
            }
        }
        return written
    }

    private fun settle(first: Boolean = false) {
        val deadline = System.currentTimeMillis() + if (first) 20_000 else 8_000
        var last = -1
        var stable = 0
        while (stable < 4 && System.currentTimeMillis() < deadline) {
            tick()
            val count = runCatching { compose.onAllNodes(all).fetchSemanticsNodes().size }.getOrDefault(0)
            if (count > 3 && count == last) stable++ else stable = 0
            last = count
            Thread.sleep(120)
        }
    }

    private fun tick() {
        compose.mainClock.advanceTimeBy(100)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    private fun writeReport(id: String, refusal: String?, warnings: List<String>, images: List<String>) {
        val report = buildJsonObject {
            put("ok", refusal == null)
            put("refusal", refusal?.let(::JsonPrimitive) ?: JsonNull)
            put("warnings", buildJsonArray { warnings.forEach { add(JsonPrimitive(it)) } })
            put("images", buildJsonArray { images.forEach { add(JsonPrimitive(it)) } })
        }
        File(out, "$id.report.json").writeText(report.toString() + "\n")
    }

    private companion object {
        /** Stops mid-word, so the strip shows completions rather than what follows a space. */
        const val PHRASE = "See you tomo"

        const val PANEL_PREFIX = "panel_"

        /** The pads a field of a kind brings up, and an input type that asks for each. */
        val PADS = listOf(
            LayoutLayer.NUMBER to InputType.TYPE_CLASS_NUMBER,
            LayoutLayer.PHONE to InputType.TYPE_CLASS_PHONE,
            LayoutLayer.DATE to (InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE),
            LayoutLayer.TIME to (InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME),
            LayoutLayer.DATETIME to (InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_NORMAL),
        )

        /** What the clipboard panel shows: a note, a link and a code, so its chips appear too. */
        val CLIPS = listOf(
            "Pick up milk and eggs on the way home",
            "https://wmkeyboard.app/themes/",
            "Your code is 482913",
        )
    }
}
