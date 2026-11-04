package com.wasimaster.wmkeyboard.core.settings.foreign

import com.wasimaster.wmkeyboard.core.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The other keyboards' backup formats, built here byte for byte from their
 * published shapes, and what each turns into.
 */
class ForeignBackupTest {

    // ---- the preference readers ----

    @Test
    fun heliSectionsTakeTheirTypeFromTheHeader() {
        val prefs = ForeignPrefs.heliSections(
            """
            boolean settings
            {"show_number_row":true,"popup_on":false}
            int settings
            {"vibration_duration_settings":25}
            long settings
            {}
            float settings
            {"keyboard_height_scale_false_false":1.25}
            string settings
            {"theme_colors":"dark"}
            string set settings
            {"some_set":["a","b"]}
            """.trimIndent(),
        )!!
        assertEquals(true, prefs["show_number_row"])
        assertEquals(false, prefs["popup_on"])
        assertEquals(25, prefs["vibration_duration_settings"])
        assertEquals(1.25f, prefs["keyboard_height_scale_false_false"])
        assertEquals("dark", prefs["theme_colors"])
        assertEquals(setOf("a", "b"), prefs["some_set"])
    }

    @Test
    fun heliSectionsRefuseOtherJson() {
        assertNull(ForeignPrefs.heliSections("""{"show_number_row":true}"""))
    }

    @Test
    fun futoSharedPrefsUnwrapTheirTypes() {
        val prefs = ForeignPrefs.futoSharedPrefs(
            """{"vibrate_on":{"t":"b","v":false},"pref_key_longpress_timeout":{"t":"i","v":420},""" +
                """"x":{"t":"n","v":null},"set":{"t":"ss","v":["q"]}}""",
        )!!
        assertEquals(false, prefs["vibrate_on"])
        assertEquals(420, prefs["pref_key_longpress_timeout"])
        assertFalse("x" in prefs)
        assertEquals(setOf("q"), prefs["set"])
    }

    @Test
    fun datastoreProtobufReadsEveryValueKind() {
        val bytes = preferenceMap(
            "flag" to boolValue(true),
            "count" to intValue(300),
            "name" to stringValue("qwerty"),
            "volume" to floatValue(0.5f),
            "subtypes" to setValue("en_US:KeyboardLayoutSet=qwerty:", "de_DE::"),
        )
        val prefs = ForeignPrefs.datastore(bytes)!!
        assertEquals(true, prefs["flag"])
        assertEquals(300, prefs["count"])
        assertEquals("qwerty", prefs["name"])
        assertEquals(0.5f, prefs["volume"])
        assertEquals(setOf("en_US:KeyboardLayoutSet=qwerty:", "de_DE::"), prefs["subtypes"])
    }

    @Test
    fun datastoreRefusesTruncatedBytes() {
        val bytes = preferenceMap("name" to stringValue("qwerty"))
        assertNull(ForeignPrefs.datastore(bytes.copyOf(bytes.size - 3)))
    }

    @Test
    fun jetprefUnquotesStrings() {
        val prefs = ForeignPrefs.jetpref(
            """
            b;keyboard__number_row;true
            i;keyboard__long_press_delay;350
            s;theme__day_theme_id;"org.florisboard.themes:floris_day"
            s;escaped;"a\"b\\c\nd"
            """.trimIndent(),
        )!!
        assertEquals(true, prefs["keyboard__number_row"])
        assertEquals(350, prefs["keyboard__long_press_delay"])
        assertEquals("org.florisboard.themes:floris_day", prefs["theme__day_theme_id"])
        assertEquals("a\"b\\c\nd", prefs["escaped"])
    }

    @Test
    fun anySoftKeyboardReadsTheSharedPreferencesProvider() {
        val prefs = ForeignPrefs.anySoftKeyboard(ASK_XML)!!
        assertEquals(false, prefs["auto_caps"])
        assertEquals(true, prefs["sound_on"])
    }

    @Test
    fun anySoftKeyboardRefusesADoctype() {
        assertNull(ForeignPrefs.anySoftKeyboard(ASK_XML.replace("<AnySoftKeyboardPrefs", "<!DOCTYPE x><AnySoftKeyboardPrefs")))
    }

    // ---- the mapping ----

    @Test
    fun heliSettingsConvertOntoOurs() {
        val mapped = ForeignSettingsMap.map(
            ForeignKeyboard.HELIBOARD,
            mapOf(
                "show_number_row" to false,
                "vibration_duration_settings" to -1,
                "key_longpress_timeout" to 5000,
                "keyboard_height_scale_false_false" to 1.25f,
                "theme_colors" to "black",
            ),
        ).associateBy { it.kind }
        assertEquals(false, mapped.getValue(ForeignSettingKind.NUMBER_ROW).value)
        // −1 means "the system's", which is what ours does with nothing set.
        assertFalse(ForeignSettingKind.HAPTIC_STRENGTH in mapped)
        assertEquals(3000, mapped.getValue(ForeignSettingKind.LONG_PRESS_DELAY).value)
        assertEquals(60, mapped.getValue(ForeignSettingKind.KEY_HEIGHT).value)
        assertEquals(ThemeMode.AMOLED.name, mapped.getValue(ForeignSettingKind.THEME_MODE).value)
    }

    @Test
    fun leanTypeHeightKeyIsReadToo() {
        val mapped = ForeignSettingsMap.map(ForeignKeyboard.LEANTYPE, mapOf("keyboard_height_scale_false" to 0.8f))
        assertEquals(38, mapped.single().value)
    }

    @Test
    fun anEntryCarriesOurKeyAndType() {
        val entry = ForeignSettingsMap.map(ForeignKeyboard.FUTO, mapOf("vibration_duration" to 30)).single().entry
        assertEquals("haptic_strength", entry.name)
        assertEquals("int", entry.type)
        assertEquals(30, entry.value)
    }

    // ---- whole backups ----

    /**
     * HeliBoard's customised toolbar long presses come across as hold bindings
     * (issue #639). The tap half is left behind, as is a long press that only
     * restates the pairing this app already gives, or one with no tool here.
     */
    @Test
    fun `heliboard toolbar long presses map to hold bindings`() {
        val codes = "UNDO,null,-32;COPY,null,-32;CLIPBOARD,-213,0;LEFT,null,-11000;PAGE_UP,null,-25;REDO,-131,null"
        val mapped = ForeignSettingsMap.map(ForeignKeyboard.HELIBOARD, mapOf("toolbar_custom_key_codes" to codes))
            .single { it.kind == ForeignSettingKind.TOOL_HOLD_ACTIONS }
        // UNDO holds to cut; COPY's cut is the pairing already; CLIPBOARD's 0
        // is a hold that does nothing; the repeat, page start and a tap-only
        // entry have nothing to bind.
        assertEquals("UNDO=CUT,CLIPBOARD=NONE", mapped.value)
        assertEquals("toolbar_hold_actions", mapped.entry.name)
    }

    @Test
    fun `an untouched heliboard toolbar imports no hold bindings`() {
        val mapped = ForeignSettingsMap.map(ForeignKeyboard.HELIBOARD, mapOf("toolbar_custom_key_codes" to ""))
        assertTrue(mapped.none { it.kind == ForeignSettingKind.TOOL_HOLD_ACTIONS })
    }

    @Test
    fun heliBoardBackupOffersEachPart() {
        val layoutName = "My layout"
        val layoutFile = "custom.Latn.${BigInteger(1, layoutName.toByteArray()).toString(36)}."
        val prefs = """
            boolean settings
            {"show_number_row":true,"theme_auto_day_night":false}
            int settings
            {"user_more_colors_Night":0}
            long settings
            {}
            float settings
            {}
            string settings
            {"theme_colors":"Night","enabled_subtypes":"en-US§KeyboardLayoutSet=MAIN:qwerty;de§KeyboardLayoutSet=MAIN:$layoutFile","user_colors_Night":"[{\"name\":\"background\",\"color\":-16777216,\"auto\":false},{\"name\":\"keys\",\"color\":-14540254,\"auto\":false}]"}
            string set settings
            {}
        """.trimIndent()
        val zip = zip(
            "preferences.json" to prefs,
            "unprotected/layouts/main/$layoutFile" to "q\nw\ne\n\na\ns\nd\n\nz\nx\nc",
            "unprotected/layouts/symbols/custom.abc." to "1\n2",
            "blacklists/en_US.txt" to "badword\n",
            "heliboard.db" to "sqlite",
        )
        val backup = ForeignBackups.read(zip.inputStream())!!
        assertEquals(ForeignKeyboard.HELIBOARD, backup.keyboard)

        val layout = backup.layouts.single()
        assertEquals(layoutFile, layout.ref)
        assertEquals(layoutName, layout.converted.layout.name)

        val theme = backup.themes.single()
        assertEquals("Night", theme.name)
        assertEquals("Night", backup.activeThemeRef)

        assertEquals(2, backup.languages.size)
        assertEquals("en", backup.languages[0].langId)
        assertEquals(layoutFile, backup.languages[1].customRef)

        assertEquals(listOf("badword"), backup.blacklist)
        assertTrue(backup.settings.any { it.kind == ForeignSettingKind.NUMBER_ROW })
        val left = backup.leftBehind.associate { it.kind to it.count }
        assertEquals(1, left[LeftBehind.Kind.OTHER_LAYOUTS])
        assertEquals(1, left[LeftBehind.Kind.CLIPBOARD])
    }

    @Test
    fun leanTypeIsToldApartByItsExtraFile() {
        val zip = zip(
            "preferences.json" to "boolean settings\n{\"show_number_row\":true}",
            "floating_keyboard_preferences.json" to "boolean settings\n{}",
        )
        assertEquals(ForeignKeyboard.LEANTYPE, ForeignBackups.read(zip.inputStream())!!.keyboard)
    }

    @Test
    fun futoBackupSkipsModelsAndReadsPersonalWords() {
        val datastore = preferenceMap(
            "vibration_duration" to intValue(20),
            "subtypes" to setValue("en_US:KeyboardLayoutSet=qwerty:"),
            "suggestionBlacklist" to setValue("nope"),
            "activeThemeOption" to stringValue("custommidnight_"),
        )
        val theme = zip(
            "theme.txt" to "name = \"Midnight\"\n[colors]\nkeyboard_surface = \"#101418\"\n" +
                "keyboard_container = \"#2A2F3A\"\nprimary = \"#8AB4F8\"\n",
        )
        val zip = zip(
            "FUTOKeyboardSettings_CfgExportVersion" to "\u0001",
            "datastore.preferences_pb" to datastore,
            "sharedPreferences.json" to """{"sound_on":{"t":"b","v":true}}""",
            "userdictionary.json" to """[{"word":"Wasi","frequency":250,"locale":"en_US","shortcut":"ws"}]""",
            "transformers/model.gguf" to "big",
            "themes/abc.zip" to "theme",
            "themes/midnight.zip" to theme,
        )
        val backup = ForeignBackups.read(zip.inputStream())!!
        assertEquals(ForeignKeyboard.FUTO, backup.keyboard)
        assertEquals(listOf(ForeignWord("Wasi", "en")), backup.personalWords)
        assertEquals(listOf("nope"), backup.blacklist)
        assertEquals("en", backup.languages.single().langId)
        val kinds = backup.settings.map { it.kind }.toSet()
        assertTrue(ForeignSettingKind.HAPTIC_STRENGTH in kinds)
        assertTrue(ForeignSettingKind.KEY_SOUND in kinds)
        val left = backup.leftBehind.associate { it.kind to it.count }
        assertEquals(1, left[LeftBehind.Kind.MODELS])
        assertEquals(1, left[LeftBehind.Kind.THEME_FILES])
        assertEquals("Midnight", backup.themes.single().name)
        assertEquals("midnight", backup.activeThemeRef)
        assertEquals(1, left[LeftBehind.Kind.SHORTCUTS])
    }

    @Test
    fun florisBackupReadsPrefsAndLanguages() {
        val zip = zip(
            "backup_metadata.json" to """{"package":"dev.patrickgold.florisboard","versionCode":100}""",
            "jetpref_datastore/florisboard-app-prefs.jetpref" to
                "b;keyboard__number_row;true\n" +
                "s;theme__mode;\"ALWAYS_NIGHT\"\n" +
                "s;localization__subtypes;\"[{\\\"id\\\":1,\\\"primaryLocale\\\":\\\"de_DE\\\"," +
                "\\\"layoutMap\\\":{\\\"characters\\\":\\\"org.florisboard.layouts:qwertz\\\"}}]\"",
            "clipboard/clipboard_text_items.json" to "[]",
        )
        val backup = ForeignBackups.read(zip.inputStream())!!
        assertEquals(ForeignKeyboard.FLORISBOARD, backup.keyboard)
        assertEquals("de", backup.languages.single().langId)
        val mapped = backup.settings.associateBy { it.kind }
        assertEquals(true, mapped.getValue(ForeignSettingKind.NUMBER_ROW).value)
        assertEquals(ThemeMode.DARK.name, mapped.getValue(ForeignSettingKind.THEME_MODE).value)
    }

    @Test
    fun anUnrelatedZipIsNotABackup() {
        assertNull(ForeignBackups.read(zip("readme.txt" to "hello").inputStream()))
        assertFalse(ForeignBackups.looksLikeBackup(listOf("readme.txt")))
        assertTrue(ForeignBackups.looksLikeBackup(listOf("x", "preferences.json")))
    }

    @Test
    fun anySoftKeyboardTextBecomesABackup() {
        val backup = ForeignBackups.readText(ASK_XML)
        assertNotNull(backup)
        assertEquals(ForeignKeyboard.ANYSOFTKEYBOARD, backup!!.keyboard)
        assertTrue(backup.settings.any { it.kind == ForeignSettingKind.AUTO_CAPITALIZE && it.value == false })
    }

    @Test
    fun cleverKeysCustomLayoutsComeAcross() {
        // The `layouts` list holds a built-in grid by name, then a custom one
        // as its XML with the language the user bound it to.
        val text = """
            {"metadata":{"app_version":"1.2.0"},"preferences":{"vibrate_custom":true,
            "layouts":["latn_qwerty_us",{"kind":"custom","language":"de",
            "xml":"<keyboard name=\"Mine\"><row><key c=\"a\" ne=\"1\"/><key c=\"b\"/></row></keyboard>"}]}}
        """.trimIndent()
        val backup = ForeignBackups.readText(text)
        assertNotNull(backup)
        val layout = backup!!.layouts.single()
        assertEquals("Mine", layout.converted.layout.name)
        assertEquals("de", layout.langId)
    }

    // ---- Kinetica ----

    @Test
    fun kineticaPrefsAreTypedByTheirRecord() {
        val prefs = ForeignPrefs.kinetica(
            kineticaBackup(
                "pref\tbool\tpref_number_row\ttrue",
                "pref\tint\tpref_long_press_ms\t350",
                "pref\tset\tpref_enabled_languages\tde,en",
                "pref\tstring\tpref_comma_custom\t",
                "pref\tstring\tpref_no_space_before\ta\tb",
                "word\ten\thello\t3",
            ),
        )!!
        assertEquals(true, prefs["pref_number_row"])
        assertEquals(350, prefs["pref_long_press_ms"])
        assertEquals(setOf("de", "en"), prefs["pref_enabled_languages"])
        // An empty value is a value, and one with a tab in it is joined back.
        assertEquals("", prefs["pref_comma_custom"])
        assertEquals("a\tb", prefs["pref_no_space_before"])
        assertFalse("hello" in prefs)
        assertNull(ForeignPrefs.kinetica("pref\tbool\tpref_number_row\ttrue"))
    }

    @Test
    fun kineticaBackupOffersEachPart() {
        val backup = ForeignBackups.readText(
            kineticaBackup(
                "pref\tstring\tpref_theme_mode\tcustom",
                "pref\tstring\tpref_theme_color_hex\t#2E7D32",
                "pref\tstring\tpref_theme_brightness\tsystem",
                "pref\tstring\tpref_language\tde",
                "pref\tset\tpref_enabled_languages\tde,en",
                "pref\tstring\tpref_key_arrangement\tqwertz",
                "pref\tbool\tpref_vibration\tfalse",
                "pref\tstring\tpref_autocorrect_level\toff",
                "pref\tstring\tpref_layout_mode\tsplit",
                "pref\tint\tpref_long_press_ms\t350",
                "word\ten\tgithub\t5",
                "wcase\ten\tgithub\tGitHub\t3",
                "word\ten\tqd\t1",
                "word\tde\thallo\t2",
                "phrase\ten\tgood\tmorning\t4",
                "block\ten\tducking",
                "expand\tbrb\t0\tbe right back",
                "expand\tsig\t0\tWasi\\nDhaka",
                "expand\tsig\t1\tW.",
                "expand\t^^\t0\t^_^",
                "chord\tq\tquestion",
                "basedict\ten",
                "mystery\tfrom a newer build",
            ),
        )!!
        assertEquals(ForeignKeyboard.KINETICA, backup.keyboard)

        val theme = backup.themes.single() as ForeignTheme.Kinetica
        assertEquals("Kinetica #2E7D32", theme.name)
        assertEquals(theme.ref, backup.activeThemeRef)
        // Following the phone comes across as a family with a light half.
        assertEquals(1, theme.result.theme.variants.size)

        // The language in use first, with the arrangement the user chose.
        assertEquals(listOf("de", "en"), backup.languages.map { it.langId })
        assertTrue(backup.languages[0].layoutId.endsWith("qwertz"))

        val settings = backup.settings.associateBy { it.kind }
        assertEquals(false, settings.getValue(ForeignSettingKind.HAPTICS).value)
        assertEquals(false, settings.getValue(ForeignSettingKind.AUTOCORRECT).value)
        assertEquals(true, settings.getValue(ForeignSettingKind.SPLIT_KEYBOARD).value)
        assertEquals(350, settings.getValue(ForeignSettingKind.LONG_PRESS_DELAY).value)
        assertEquals(ThemeMode.SYSTEM.name, settings.getValue(ForeignSettingKind.THEME_MODE).value)

        // Learned words as a learned list per language, with their capitals
        // and pairs; a word counted once never reached its suggestions.
        val english = backup.dictionaries.single { it.langId == "en" }
        assertTrue(english.learned)
        assertEquals(listOf("GitHub" to 5), english.candidate.words)
        assertEquals(listOf("good"), english.candidate.ngrams.single().context)
        assertEquals("morning", english.candidate.ngrams.single().word)
        assertEquals(listOf("hallo" to 2), backup.dictionaries.single { it.langId == "de" }.candidate.words)

        assertEquals(listOf("ducking"), backup.blacklist)

        val snippets = backup.snippets.associateBy { it.text }
        assertEquals("brb", snippets.getValue("be right back").trigger)
        // A target written over several lines is unescaped, and a trigger's
        // second target is offered beside the first.
        val signature = snippets.getValue("Wasi\nDhaka")
        assertEquals("sig", signature.trigger)
        assertEquals(listOf("W."), signature.alternates)
        assertTrue(signature.confirm)
        // A trigger the keyboard cannot watch for keeps the snippet, without it.
        assertNull(snippets.getValue("^_^").trigger)

        val left = backup.leftBehind.associate { it.kind to it.count }
        assertEquals(1, left[LeftBehind.Kind.CHORDS])
        assertEquals(1, left[LeftBehind.Kind.BASE_DICTIONARIES])
    }

    @Test
    fun aKineticaBackupWithNothingElseIsStillItsTheme() {
        val backup = ForeignBackups.readText(kineticaBackup())!!
        assertEquals("Kinetica", backup.themes.single().name)
        assertTrue(backup.settings.isEmpty())
    }

    @Test
    fun kineticaUnescapesAsItsRestoreDoes() {
        assertEquals("a\tb\nc\\d", ForeignBackups.unescapeKinetica("a\\tb\\nc\\\\d"))
        // An unknown escape keeps its backslash, and so does a trailing one.
        assertEquals("\\x\\", ForeignBackups.unescapeKinetica("\\x\\"))
    }

    /**
     * Real backups, when there are some to hand: every file in the folder
     * named by `FOREIGN_BACKUPS` must read, and offer something. Skips when the
     * variable is unset, which is every run but a deliberate one.
     */
    @Test
    fun corpusSweep() {
        val dir = System.getenv("FOREIGN_BACKUPS")?.let(::File)?.takeIf { it.isDirectory } ?: return
        for (file in dir.listFiles().orEmpty().filter { it.isFile }) {
            val backup = file.inputStream().use { ForeignBackups.read(it) }
                ?: ForeignBackups.readText(file.readText())
            assertNotNull("${file.name} read as nothing", backup)
            println("${file.name}: ${backup!!.keyboard} layouts=${backup.layouts.size} themes=${backup.themes.size} " +
                "languages=${backup.languages.size} settings=${backup.settings.size} " +
                "dictionaries=${backup.dictionaries.size} words=${backup.personalWords.size} left=${backup.leftBehind}")
        }
    }

    // ---- building the files ----

    private fun kineticaBackup(vararg records: String): String =
        (listOf("kinetica-backup\t1") + records).joinToString("\n")

    private fun zip(vararg entries: Pair<String, Any>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(if (content is ByteArray) content else content.toString().toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return out.toByteArray()
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun field(number: Int, wire: Int, payload: ByteArray): ByteArray =
        varint(((number shl 3) or wire).toLong()) + payload

    private fun lengthDelimited(number: Int, payload: ByteArray): ByteArray =
        field(number, 2, varint(payload.size.toLong()) + payload)

    private fun boolValue(v: Boolean) = field(1, 0, varint(if (v) 1 else 0))
    private fun intValue(v: Int) = field(3, 0, varint(v.toLong()))
    private fun stringValue(v: String) = lengthDelimited(5, v.toByteArray())
    private fun floatValue(v: Float): ByteArray {
        val bits = v.toRawBits()
        return field(2, 5, ByteArray(4) { (bits ushr (8 * it)).toByte() })
    }
    private fun setValue(vararg strings: String) =
        lengthDelimited(6, strings.fold(ByteArray(0)) { acc, s -> acc + lengthDelimited(1, s.toByteArray()) })

    private fun preferenceMap(vararg entries: Pair<String, ByteArray>): ByteArray =
        entries.fold(ByteArray(0)) { acc, (key, value) ->
            acc + lengthDelimited(1, lengthDelimited(1, key.toByteArray()) + lengthDelimited(2, value))
        }

    private companion object {
        val ASK_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <AnySoftKeyboardPrefs version="1">
            <pref><value providerId="SharedPreferences"/><value version="11"/>
            <pref><value type="bool"/><value auto_caps="false"/></pref>
            <pref><value type="bool"/><value sound_on="true"/></pref>
            </pref>
            </AnySoftKeyboardPrefs>
        """.trimIndent()
    }
}
