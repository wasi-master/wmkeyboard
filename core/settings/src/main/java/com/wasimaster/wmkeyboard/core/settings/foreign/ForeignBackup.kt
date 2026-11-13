package com.wasimaster.wmkeyboard.core.settings.foreign

import com.wasimaster.wmkeyboard.core.layout.ConvertedLayout
import com.wasimaster.wmkeyboard.core.layout.ForeignLayouts
import com.wasimaster.wmkeyboard.core.layout.FutoLayouts
import com.wasimaster.wmkeyboard.core.layout.UnexpectedLayouts
import com.wasimaster.wmkeyboard.core.prediction.AospDictionary
import com.wasimaster.wmkeyboard.core.prediction.CustomDictionaries
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.snippets.MultiExpand
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.snippets.SnippetMatcher
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.core.theme.FlexResult
import com.wasimaster.wmkeyboard.core.theme.FlexTheme
import com.wasimaster.wmkeyboard.core.theme.FutoTheme
import com.wasimaster.wmkeyboard.core.theme.FutoThemeResult
import com.wasimaster.wmkeyboard.core.theme.HeliResult
import com.wasimaster.wmkeyboard.core.theme.HeliTheme
import com.wasimaster.wmkeyboard.core.theme.HeliUnsupported
import com.wasimaster.wmkeyboard.core.theme.KineticaTheme
import com.wasimaster.wmkeyboard.core.theme.KineticaThemeResult
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.WallpaperPalette
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Everything another keyboard's backup holds that this app can take, read and
 * converted, with nothing written yet.
 *
 * Each list is a set of things the user can tick or leave: the picker shows one
 * row per layout, theme, language, dictionary and setting, and only the ticked
 * ones are stored. [leftBehind] is what the file holds that has nowhere to go
 * here, so the picker can say so instead of letting it vanish.
 */
data class ForeignBackup(
    val keyboard: ForeignKeyboard,
    val layouts: List<ForeignLayout> = emptyList(),
    val themes: List<ForeignTheme> = emptyList(),
    /** The [ForeignTheme.ref] of the theme the other keyboard had on, when it was one of [themes]. */
    val activeThemeRef: String? = null,
    val languages: List<ForeignLanguage> = emptyList(),
    val settings: List<MappedSetting> = emptyList(),
    val dictionaries: List<ForeignDictionary> = emptyList(),
    /** Words the user added by hand there, which are words they vouched for here too. */
    val personalWords: List<ForeignWord> = emptyList(),
    /** Words the user told the other keyboard never to suggest. */
    val blacklist: List<String> = emptyList(),
    /** Text expansions, as snippets with their ids still to be given. */
    val snippets: List<Snippet> = emptyList(),
    val leftBehind: List<LeftBehind> = emptyList(),
) {
    /** Whether there is anything at all to offer. */
    val isEmpty: Boolean
        get() = layouts.isEmpty() && themes.isEmpty() && languages.isEmpty() && settings.isEmpty() &&
            dictionaries.isEmpty() && personalWords.isEmpty() && blacklist.isEmpty() && snippets.isEmpty()
}

/**
 * A custom layout from the backup. [ref] is the name the other keyboard's own
 * language list uses for it, so a language that typed in this layout there can
 * type in it here.
 */
data class ForeignLayout(val ref: String, val converted: ConvertedLayout, val langId: String)

/** A colour theme from the backup. [ref] is its name there. */
sealed interface ForeignTheme {
    val ref: String
    val name: String

    /** A HeliBoard or LeanType colour theme, already a [ThemeSpec]. */
    data class Colours(
        override val ref: String,
        override val name: String,
        val theme: ThemeSpec,
        val dropped: List<HeliUnsupported>,
    ) : ForeignTheme

    /** A FUTO Keyboard custom theme, as the single-file import reads it. */
    data class Futo(
        override val ref: String,
        override val name: String,
        val result: FutoThemeResult.Converted,
    ) : ForeignTheme

    /** A FlorisBoard theme extension, as the `.flex` import reads it. */
    data class Flex(
        override val ref: String,
        override val name: String,
        val result: FlexResult.Converted,
    ) : ForeignTheme

    /**
     * Kinetica's one theme, worked out from its settings as its own theme
     * import does. A family of two looks when it followed the phone's dark mode.
     */
    data class Kinetica(
        override val ref: String,
        override val name: String,
        val result: KineticaThemeResult.Converted,
    ) : ForeignTheme
}

/**
 * One language the other keyboard had on, as a layout of ours. [layoutId] is
 * the built-in layout closest to the one it used; [customRef] names the
 * backup's own layout when it used one of those, which wins when that layout
 * is imported too.
 */
data class ForeignLanguage(val langId: String, val layoutId: String, val customRef: String? = null)

/** A word list from the backup, with the language it is for. [learned] marks a typing history. */
data class ForeignDictionary(
    val candidate: CustomDictionaries.ImportCandidate,
    val langId: String,
    val learned: Boolean,
)

data class ForeignWord(val word: String, val langId: String?)

/** Something in the backup with nowhere to go here, and how many of it. */
data class LeftBehind(val kind: Kind, val count: Int) {
    enum class Kind {
        /** Clipboard history. Deliberately not imported: it is the other keyboard's, and often private. */
        CLIPBOARD,

        /** Symbol, number and other non-letter layouts, which this app draws from its own. */
        OTHER_LAYOUTS,

        /** Theme files this app could not read. */
        THEME_FILES,

        /** A custom font file. */
        FONTS,

        /** A background picture. */
        BACKGROUND_IMAGES,

        /** Language models and other heavy resources. */
        MODELS,

        /** Shortcuts on personal-dictionary words. */
        SHORTCUTS,

        /** Kinetica's chords: text typed by holding keys together, which this app has no gesture for. */
        CHORDS,

        /** Word lists the other keyboard had imported, which its backup names but does not carry. */
        BASE_DICTIONARIES,
    }
}

/**
 * Reads a backup made by another keyboard: HeliBoard, LeanType, FUTO Keyboard,
 * FlorisBoard, CleverKeys, AnySoftKeyboard or Kinetica.
 *
 * One pass over the archive keeps only the entries something here can use and
 * counts the rest, so a FUTO backup carrying a few hundred megabytes of models
 * costs a read-through rather than memory. The layouts, themes and
 * dictionaries are converted by the same readers the single-file imports use;
 * this file only knows where each keyboard puts them.
 */
object ForeignBackups {

    /** Past this, one entry is not something a backup holds; it is refused. */
    private const val MAX_ENTRY_BYTES = 32 * 1024 * 1024

    /** The defence against a zip bomb: everything kept, together. */
    private const val MAX_KEPT_BYTES = 128L * 1024 * 1024
    private const val MAX_ENTRIES = 16_384

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /**
     * Whether a ZIP's entry names are another keyboard's backup. Cheap: the
     * file-type check calls it with the names it already walked.
     */
    fun looksLikeBackup(names: Collection<String>): Boolean =
        names.any { it == HELI_PREFS || it == FUTO_VERSION || it == FLORIS_METADATA || it == CLEVER_MANIFEST }

    /**
     * Reads a backup ZIP. Null when it is none of the formats, or holds nothing
     * usable. [palette] is the phone's wallpaper palette, which a FUTO theme's
     * wallpaper colours are resolved against.
     */
    fun read(input: InputStream, palette: WallpaperPalette? = null): ForeignBackup? {
        val archive = unzip(input) ?: return null
        val backup = when {
            FUTO_VERSION in archive.kept -> futo(archive, palette)
            FLORIS_METADATA in archive.kept -> floris(archive)
            HELI_PREFS in archive.kept -> heli(archive)
            CLEVER_MANIFEST in archive.kept -> cleverKeysArchive(archive)
            else -> null
        }
        return backup?.takeUnless { it.isEmpty }
    }

    /**
     * Reads a backup that is one text file: a CleverKeys settings export,
     * AnySoftKeyboard's `AnySoftKeyboardPrefs.xml`, or a Kinetica backup.
     */
    fun readText(text: String): ForeignBackup? {
        if (KineticaTheme.looksLikeBackup(text)) return kinetica(text)?.takeUnless { it.isEmpty }
        ForeignPrefs.anySoftKeyboard(text)?.let { prefs ->
            return ForeignBackup(
                keyboard = ForeignKeyboard.ANYSOFTKEYBOARD,
                settings = ForeignSettingsMap.map(ForeignKeyboard.ANYSOFTKEYBOARD, prefs),
            ).takeUnless { it.isEmpty }
        }
        if (!ForeignPrefs.looksLikeCleverKeys(text)) return null
        val prefs = ForeignPrefs.cleverKeys(text) ?: return null
        return ForeignBackup(
            keyboard = ForeignKeyboard.CLEVERKEYS,
            layouts = cleverKeysLayouts(text),
            settings = ForeignSettingsMap.map(ForeignKeyboard.CLEVERKEYS, prefs),
        ).takeUnless { it.isEmpty }
    }

    // ---- the archive ----

    /** The entries worth keeping, by name, and every name seen. */
    private class Archive(val kept: Map<String, ByteArray>, val names: List<String>) {
        fun text(name: String): String? = kept[name]?.decodeToString()
    }

    private fun unzip(input: InputStream): Archive? = runCatching {
        val kept = LinkedHashMap<String, ByteArray>()
        val names = ArrayList<String>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            var count = 0
            while (count++ < MAX_ENTRIES) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.trimStart('/')
                // A name that climbs out is not something any keyboard writes.
                if (name.split('/').any { it == ".." }) continue
                names += name
                if (!wanted(name)) continue
                val bytes = readCapped(zip) ?: continue
                total += bytes.size
                if (total > MAX_KEPT_BYTES) return@runCatching null
                kept[name] = bytes
            }
        }
        Archive(kept, names)
    }.getOrNull()

    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_ENTRY_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** What is read into memory. Everything else is only counted. */
    private fun wanted(name: String): Boolean = name in WANTED_NAMES ||
        WANTED_PREFIXES.any { name.startsWith(it) } ||
        (name.startsWith(FUTO_EXT) && name.endsWith(".dict")) ||
        name.startsWith(USER_HISTORY)

    // ---- HeliBoard and LeanType ----

    private fun heli(archive: Archive): ForeignBackup? {
        val prefs = LinkedHashMap<String, Any>()
        for (name in listOf(HELI_PREFS, HELI_PROTECTED, LEAN_FLOATING)) {
            archive.text(name)?.let(ForeignPrefs::heliSections)?.let(prefs::putAll)
        }
        if (prefs.isEmpty()) return null
        // LeanType writes a third preferences file and names its database after
        // itself; either is enough to tell the fork apart.
        val keyboard = if (LEAN_FLOATING in archive.names || "leantype.db" in archive.names) {
            ForeignKeyboard.LEANTYPE
        } else {
            ForeignKeyboard.HELIBOARD
        }

        val layouts = ArrayList<ForeignLayout>()
        var otherLayouts = 0
        for ((name, bytes) in archive.kept) {
            val match = HELI_LAYOUT.matchEntire(name) ?: continue
            val type = match.groupValues[1].trimEnd('/')
            if (type.isNotEmpty() && type != "main") {
                otherLayouts++
                continue
            }
            heliLayout(match.groupValues[2], bytes.decodeToString())?.let(layouts::add)
        }

        val themes = heliThemes(prefs)
        val active = (prefs["theme_colors"] as? String)?.takeIf { ref -> themes.any { it.ref == ref } }

        val languages = heliLanguages(prefs)

        val blacklist = archive.kept.filterKeys { it.startsWith(HELI_BLACKLISTS) && it.endsWith(".txt") }
            .values.flatMap { bytes -> bytes.decodeToString().lines().map { it.trim() } }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .distinct()

        val left = buildList {
            if (otherLayouts > 0) add(LeftBehind(LeftBehind.Kind.OTHER_LAYOUTS, otherLayouts))
            leftCount(archive.names, LeftBehind.Kind.CLIPBOARD) {
                it.endsWith(".db") || it.startsWith("clipboard/")
            }?.let(this::add)
            leftCount(archive.names, LeftBehind.Kind.FONTS) { it.startsWith("unprotected/custom_") && it.contains("font") }
                ?.let(this::add)
            leftCount(archive.names, LeftBehind.Kind.BACKGROUND_IMAGES) {
                it.startsWith("unprotected/custom_background_image")
            }?.let(this::add)
        }

        return ForeignBackup(
            keyboard = keyboard,
            layouts = layouts,
            themes = themes,
            activeThemeRef = active,
            languages = languages,
            settings = ForeignSettingsMap.map(keyboard, prefs),
            dictionaries = dictionaries(archive) { it.startsWith(HELI_DICTS) || it.startsWith(USER_HISTORY) },
            blacklist = blacklist,
            leftBehind = left,
        )
    }

    /**
     * One HeliBoard layout file. Its name is `custom.<script or language>.<name>.`
     * for a letter layout, and the name is the user's own, base-36 encoded.
     */
    private fun heliLayout(fileName: String, text: String): ForeignLayout? {
        val parts = fileName.removePrefix("custom.").trimEnd('.').split('.')
        val encoded = parts.last()
        val qualifier = parts.dropLast(1).lastOrNull()
        val displayName = decodeBase36(encoded) ?: encoded
        val converted = ForeignLayouts.convert(text, displayName) ?: return null
        // The qualifier is a script code ("Latn") for a layout shared across a
        // script, or a language tag when it was made for one language.
        val declared = qualifier
            ?.takeUnless { it.length == SCRIPT_CODE_LENGTH && it[0].isUpperCase() }
            ?.let(LanguageRegistry::byLocale)
            ?.id
        return ForeignLayout(fileName, converted, declared ?: converted.guessedLangId)
    }

    /**
     * HeliBoard's own colour themes. Each lives in the preferences under its
     * name, in whichever of the two shapes its colour screen was set to, and is
     * handed to [HeliTheme] in the very shape that keyboard exports it as, so
     * there is one converter and not two.
     */
    private fun heliThemes(prefs: Map<String, Any>): List<ForeignTheme> {
        val names = LinkedHashSet<String>()
        for (key in prefs.keys) {
            for (prefix in HELI_THEME_PREFIXES) {
                if (key.startsWith(prefix)) names += key.removePrefix(prefix)
            }
        }
        return names.mapNotNull { name ->
            val more = (prefs["$HELI_MORE_COLORS$name"] as? Number)?.toInt() ?: 0
            val all = prefs["$HELI_ALL_COLORS$name"] as? String
            val named = prefs["$HELI_USER_COLORS$name"] as? String
            val text = when {
                more == ALL_COLOURS_MODE && all != null -> allColoursJson(name, all)
                named != null -> namedColoursJson(name, more, named)
                all != null -> allColoursJson(name, all)
                else -> null
            } ?: return@mapNotNull null
            val result = HeliTheme.read(text) as? HeliResult.Converted ?: return@mapNotNull null
            ForeignTheme.Colours(name, name, result.theme.copy(name = name), result.dropped)
        }
    }

    /** `TYPE,argb;TYPE,argb…` as the flat map, with the name folded in as that keyboard does. */
    private fun allColoursJson(name: String, packed: String): String? {
        val obj = buildJsonObject {
            for (pair in packed.split(';')) {
                val type = pair.substringBefore(',').trim()
                val argb = pair.substringAfter(',', "").trim().toIntOrNull() ?: continue
                if (type.isNotEmpty()) put(type, argb)
            }
            put(BigInteger(1, name.toByteArray()).toString(BASE36), 0)
        }
        return if (obj.size > 1) obj.toString() else null
    }

    /**
     * The ten named colours, stored either as a JSON array of
     * `{"name","color","auto"}` objects or as the `{"name":[argb,auto]}` map
     * the share text uses.
     */
    private fun namedColoursJson(name: String, more: Int, stored: String): String? {
        val parsed = runCatching { json.parseToJsonElement(stored) }.getOrNull() ?: return null
        val colours = buildJsonObject {
            when (parsed) {
                is JsonObject -> for ((key, value) in parsed) put(key, value)
                is JsonArray -> for (item in parsed) {
                    val obj = item as? JsonObject ?: continue
                    val key = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: continue
                    put(
                        key,
                        buildJsonArray {
                            add(obj["color"] ?: JsonNull)
                            add(obj["auto"] ?: JsonPrimitive(false))
                        },
                    )
                }
                else -> Unit
            }
        }
        if (colours.isEmpty()) return null
        return buildJsonObject {
            put("name", name)
            put("moreColors", more)
            put("colors", colours)
        }.toString()
    }

    /**
     * `enabled_subtypes`: entries joined by `;`, each `<language tag>§<extras>`,
     * where the extras may name the main layout as
     * `KeyboardLayoutSet=MAIN:qwertz|SYMBOLS:…`. The selected one goes first,
     * the way it was on the other keyboard.
     */
    private fun heliLanguages(prefs: Map<String, Any>): List<ForeignLanguage> {
        val entries = listOfNotNull(prefs["selected_subtype"] as? String) +
            (prefs["enabled_subtypes"] as? String).orEmpty().split(';')
        return entries.mapNotNull { entry ->
            val tag = entry.substringBefore('§').substringBefore(':').trim()
            if (tag.isEmpty()) return@mapNotNull null
            val extras = entry.substringAfter('§', "")
            val set = extras.split(',').firstOrNull { it.startsWith(LAYOUT_SET) }?.removePrefix(LAYOUT_SET)
            val main = set?.split('|')?.firstNotNullOfOrNull { part ->
                when {
                    part.startsWith("MAIN:") -> part.removePrefix("MAIN:")
                    ':' !in part -> part
                    else -> null
                }
            }
            if (main != null && main.startsWith("custom.")) {
                language(tag, null)?.copy(customRef = main)
            } else {
                language(tag, main)
            }
        }.distinctBy { it.customRef ?: it.layoutId }
    }

    // ---- FUTO Keyboard ----

    private fun futo(archive: Archive, palette: WallpaperPalette?): ForeignBackup? {
        val prefs = LinkedHashMap<String, Any>()
        archive.kept[FUTO_DATASTORE]?.let(ForeignPrefs::datastore)?.let(prefs::putAll)
        archive.text(FUTO_SHARED)?.let(ForeignPrefs::futoSharedPrefs)?.let(prefs::putAll)

        // Custom layouts sit in one preference as a JSON list of
        // {"language","layoutYaml"}; the language list names them `custom<index>`.
        val layouts = ArrayList<ForeignLayout>()
        val stored = (prefs[FUTO_LAYOUTS] as? String)
            ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() as? JsonArray }
            .orEmpty()
        stored.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: return@forEachIndexed
            val yaml = (obj["layoutYaml"] as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
            val converted = FutoLayouts.convert(yaml, "FUTO ${index + 1}") ?: return@forEachIndexed
            val declared = (obj["language"] as? JsonPrimitive)?.contentOrNull?.let(LanguageRegistry::byLocale)?.id
            layouts += ForeignLayout("custom$index", converted, declared ?: converted.guessedLangId)
        }

        // `en_US:KeyboardLayoutSet=qwerty:` — the locale, then the extras.
        val subtypes = listOfNotNull(prefs["activeSubtype"] as? String) +
            (prefs["subtypes"] as? Set<*>).orEmpty().filterIsInstance<String>().sorted()
        val languages = subtypes.mapNotNull { entry ->
            val parts = entry.split(':')
            val tag = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val layout = parts.getOrNull(1).orEmpty().split(',')
                .firstOrNull { it.startsWith(LAYOUT_SET) }?.removePrefix(LAYOUT_SET)
            if (layout != null && layout.startsWith("custom")) {
                language(tag, null)?.copy(customRef = layout)
            } else {
                language(tag, layout)
            }
        }.distinctBy { it.customRef ?: it.layoutId }

        var shortcuts = 0
        val words = (archive.text(FUTO_PERSONAL)
            ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() as? JsonArray })
            .orEmpty()
            .mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val word = (obj["word"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                if (!(obj["shortcut"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) shortcuts++
                val locale = (obj["locale"] as? JsonPrimitive)?.contentOrNull
                ForeignWord(word, locale?.let(LanguageRegistry::byLocale)?.id)
            }
            .distinctBy { it.word }

        val blacklist = (prefs[FUTO_BLACKLIST] as? Set<*>).orEmpty().filterIsInstance<String>()
            .map { it.trim() }.filter { it.isNotEmpty() }

        // Custom themes travel as the ZIPs FUTO keeps them in, named by their
        // id; the theme in use is `custom<id>`, with a trailing `_` when it
        // follows the system's dark mode.
        val themeFiles = archive.kept.filterKeys { it.startsWith(FUTO_THEMES) && it.endsWith(".zip") }
        val themes = themeFiles.mapNotNull { (name, bytes) ->
            val result = FutoTheme.read(bytes.inputStream(), palette) as? FutoThemeResult.Converted
                ?: return@mapNotNull null
            val ref = name.removePrefix(FUTO_THEMES).removeSuffix(".zip")
            ForeignTheme.Futo(ref, result.converted.theme.name, result)
        }
        val active = (prefs[FUTO_ACTIVE_THEME] as? String)
            ?.takeIf { it.startsWith(FUTO_CUSTOM_THEME) }
            ?.removePrefix(FUTO_CUSTOM_THEME)?.trimEnd('_')
            ?.takeIf { ref -> themes.any { it.ref == ref } }

        val left = buildList {
            (themeFiles.size - themes.size).takeIf { it > 0 }
                ?.let { add(LeftBehind(LeftBehind.Kind.THEME_FILES, it)) }
            leftCount(archive.names, LeftBehind.Kind.CLIPBOARD) { it.startsWith("clipboard") }?.let(this::add)
            leftCount(archive.names, LeftBehind.Kind.MODELS) {
                it.startsWith("transformers/") || it.startsWith("mozc/") || it.startsWith("rime/") ||
                    (it.startsWith(FUTO_EXT) && !it.endsWith(".dict"))
            }?.let(this::add)
            if (shortcuts > 0) add(LeftBehind(LeftBehind.Kind.SHORTCUTS, shortcuts))
        }

        return ForeignBackup(
            keyboard = ForeignKeyboard.FUTO,
            layouts = layouts,
            themes = themes,
            activeThemeRef = active,
            languages = languages,
            settings = ForeignSettingsMap.map(ForeignKeyboard.FUTO, prefs),
            dictionaries = dictionaries(archive) { it.startsWith(FUTO_EXT) || it.startsWith(FUTO_USERDICT) },
            personalWords = words,
            blacklist = blacklist,
            leftBehind = left,
        )
    }

    // ---- FlorisBoard ----

    private fun floris(archive: Archive): ForeignBackup? {
        val metadata = archive.text(FLORIS_METADATA) ?: return null
        if (!metadata.contains("florisboard")) return null
        val prefs = archive.text(FLORIS_PREFS)?.let(ForeignPrefs::jetpref).orEmpty()

        val layouts = archive.kept.mapNotNull { (name, bytes) ->
            val match = FLORIS_LAYOUT.matchEntire(name) ?: return@mapNotNull null
            val (extension, id) = match.destructured
            val converted = ForeignLayouts.fromFlorisJson(bytes.decodeToString(), id) ?: return@mapNotNull null
            ForeignLayout("$extension:$id", converted, converted.guessedLangId)
        }

        val themes = archive.kept.keys
            .mapNotNull { FLORIS_THEME.matchEntire(it)?.groupValues?.get(1) }
            .distinct()
            .mapNotNull { extension -> florisTheme(archive, extension) }

        val night = prefs["theme__mode"] == "ALWAYS_NIGHT"
        val activeId = (prefs[if (night) "theme__night_theme_id" else "theme__day_theme_id"] as? String)
        val active = activeId?.substringBefore(':')?.takeIf { ref -> themes.any { it.ref == ref } }

        val languages = florisLanguages(prefs["localization__subtypes"] as? String)

        val left = buildList {
            leftCount(archive.names, LeftBehind.Kind.CLIPBOARD) { it.startsWith("clipboard/") }?.let(this::add)
        }

        return ForeignBackup(
            keyboard = ForeignKeyboard.FLORISBOARD,
            layouts = layouts,
            themes = themes,
            activeThemeRef = active,
            languages = languages,
            settings = ForeignSettingsMap.map(ForeignKeyboard.FLORISBOARD, prefs),
            leftBehind = left,
        )
    }

    /**
     * One theme extension, unpacked in the backup, packed again so the `.flex`
     * reader can read it as the file it was before it was installed.
     */
    private fun florisTheme(archive: Archive, extension: String): ForeignTheme? {
        val prefix = "$FLORIS_THEMES$extension/"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in archive.kept) {
                if (!name.startsWith(prefix)) continue
                zip.putNextEntry(ZipEntry(name.removePrefix(prefix)))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val result = runCatching { FlexTheme.read(out.toByteArray().inputStream()) }.getOrNull()
            as? FlexResult.Converted ?: return null
        if (result.themes.isEmpty()) return null
        return ForeignTheme.Flex(extension, result.title.ifBlank { extension }, result)
    }

    /**
     * `localization__subtypes`: a JSON list of subtypes, each with a primary
     * locale and a layout map whose `characters` entry is `<extension>:<layout>`.
     * FlorisBoard's own layouts live in `org.florisboard.layouts`, and are read
     * by name; any other extension is one of the backup's own.
     */
    private fun florisLanguages(text: String?): List<ForeignLanguage> {
        val list = text?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() as? JsonArray }
            ?: return emptyList()
        return list.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val locale = when (val primary = obj["primaryLocale"]) {
                is JsonPrimitive -> primary.contentOrNull
                is JsonObject -> (primary["language"] as? JsonPrimitive)?.contentOrNull
                else -> null
            } ?: return@mapNotNull null
            val characters = ((obj["layoutMap"] as? JsonObject)?.get("characters") as? JsonPrimitive)?.contentOrNull
            val extension = characters?.substringBefore(':')
            if (characters != null && extension != FLORIS_OWN_LAYOUTS) {
                language(locale, null)?.copy(customRef = characters)
            } else {
                language(locale, characters?.substringAfter(':'))
            }
        }.distinctBy { it.customRef ?: it.layoutId }
    }

    // ---- CleverKeys ----

    private fun cleverKeysArchive(archive: Archive): ForeignBackup? {
        val manifest = archive.text(CLEVER_MANIFEST) ?: return null
        if (!manifest.contains("cleverkeys")) return null
        val config = archive.text(CLEVER_CONFIG) ?: return null
        val prefs = ForeignPrefs.cleverKeys(config) ?: return null
        val left = buildList {
            leftCount(archive.names, LeftBehind.Kind.CLIPBOARD) { it.startsWith("clipboard") }?.let(this::add)
        }
        return ForeignBackup(
            keyboard = ForeignKeyboard.CLEVERKEYS,
            layouts = cleverKeysLayouts(config),
            settings = ForeignSettingsMap.map(ForeignKeyboard.CLEVERKEYS, prefs),
            leftBehind = left,
        )
    }

    /**
     * The custom layouts in a CleverKeys settings export. They sit in the
     * `layouts` preference, a list whose custom entries are
     * `{"kind":"custom","xml":"<keyboard …>","language":"fa"}`: an Unexpected
     * Keyboard layout, with the language the user bound it to, if any. The
     * export writes the list as JSON; an older one wrote it as a string of
     * JSON, and both are read. The built-in layouts in the same list name
     * CleverKeys' own grids and are not layouts to bring over.
     */
    private fun cleverKeysLayouts(config: String): List<ForeignLayout> {
        val root = runCatching { json.parseToJsonElement(config) }.getOrNull() as? JsonObject ?: return emptyList()
        val list = when (val stored = (root["preferences"] as? JsonObject)?.get(CLEVER_LAYOUTS)) {
            is JsonArray -> stored
            is JsonPrimitive -> stored.contentOrNull
                ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() as? JsonArray }
            else -> null
        } ?: return emptyList()
        return list.mapIndexedNotNull { index, entry ->
            val obj = entry as? JsonObject ?: return@mapIndexedNotNull null
            if ((obj["kind"] as? JsonPrimitive)?.contentOrNull != "custom") return@mapIndexedNotNull null
            val xml = (obj["xml"] as? JsonPrimitive)?.contentOrNull ?: return@mapIndexedNotNull null
            val bound = (obj["language"] as? JsonPrimitive)?.contentOrNull
            val converted = UnexpectedLayouts.convert(xml, "CleverKeys ${index + 1}", bound)
                ?: return@mapIndexedNotNull null
            ForeignLayout("custom$index", converted, converted.guessedLangId)
        }
    }

    // ---- Kinetica ----

    /**
     * A Kinetica backup (`kinetica_backup_<date>.txt`): a header line
     * `kinetica-backup<TAB>1`, then one record a line, fields split by tabs.
     *
     * ```
     * pref<TAB>type<TAB>key<TAB>value      a setting
     * word<TAB>lang<TAB>word<TAB>count      a word learned from typing, lowercase
     * wcase<TAB>lang<TAB>word<TAB>display<TAB>votes   the capital it is written with
     * phrase<TAB>lang<TAB>prev<TAB>next<TAB>count     a pair learned from typing
     * block<TAB>lang<TAB>word               never suggested
     * expand<TAB>trigger<TAB>position<TAB>target      a text expansion, target escaped
     * chord<TAB>chord<TAB>expansion         text typed by holding keys together
     * basedict<TAB>lang                     a word list it had imported, not carried
     * ```
     *
     * Each record is checked the way Kinetica's own restore checks it, and a
     * line that fails is skipped rather than failing the file.
     *
     * Kinetica's words are what it learned as the user typed, misfires and
     * all, so they come across as a learned list per language, the way a
     * HeliBoard typing history does, rather than as words the user vouched
     * for. A word Kinetica had counted once is one it never suggested either
     * (it waits for a second use), and is left out.
     */
    private fun kinetica(text: String): ForeignBackup? {
        val prefs = ForeignPrefs.kinetica(text) ?: return null
        val words = LinkedHashMap<String, MutableList<Pair<String, Int>>>()
        val capitals = HashMap<Pair<String, String>, String>()
        val phrases = LinkedHashMap<String, MutableList<AospDictionary.Ngram>>()
        val blocked = LinkedHashSet<String>()
        val expansions = ArrayList<Triple<String, Int, String>>()
        var chords = 0
        var baseLists = 0
        for (raw in text.lineSequence()) {
            val f = raw.trimEnd('\r').split('\t')
            when (f[0]) {
                "word" -> if (f.size == 4 && f[1].isNotEmpty() && f[2].isNotEmpty()) {
                    val count = f[3].toIntOrNull() ?: continue
                    if (count >= KINETICA_MIN_COUNT) words.getOrPut(f[1]) { ArrayList() } += f[2] to count
                }
                // Kinetica keeps a capital only when lowering it gives the word back.
                "wcase" -> if (f.size == 5 && f[1].isNotEmpty() && f[2].isNotEmpty() && f[3].lowercase() == f[2]) {
                    capitals[f[1] to f[2]] = f[3]
                }
                "phrase" -> if (f.size == 5 && f[1].isNotEmpty() && f[2].isNotEmpty() && f[3].isNotEmpty()) {
                    val count = f[4].toIntOrNull()?.takeIf { it >= 1 } ?: continue
                    phrases.getOrPut(f[1]) { ArrayList() } += AospDictionary.Ngram(listOf(f[2]), f[3], count)
                }
                "block" -> if (f.size == 3 && f[1].isNotEmpty()) f[2].trim().takeIf { it.isNotEmpty() }?.let(blocked::add)
                "expand" -> if (f.size == 4 && f[1].isNotEmpty()) {
                    val position = f[2].toIntOrNull()?.takeIf { it >= 0 } ?: continue
                    val target = unescapeKinetica(f[3]).takeIf { it.isNotEmpty() } ?: continue
                    expansions += Triple(f[1], position, target)
                }
                "chord" -> if (f.size == 3 && f[1].isNotEmpty() && f[2].isNotEmpty()) chords++
                "basedict" -> if (f.size == 2 && f[1].isNotEmpty()) baseLists++
            }
        }

        val theme = (KineticaTheme.read(text) as? KineticaThemeResult.Converted)
            ?.let { ForeignTheme.Kinetica(KINETICA_THEME_REF, it.theme.familyName ?: it.theme.name, it) }

        // The languages it had on, the one in use first. Its letter
        // arrangement (qwerty, qwertz, azerty…) is one setting for the board,
        // so it picks the layout of the language in use only, and only when
        // the user chose it: Kinetica sets it on its own for French.
        val active = prefs["pref_language"] as? String
        val enabled = (prefs["pref_enabled_languages"] as? Set<*>).orEmpty().filterIsInstance<String>().sorted()
        val arrangement = (prefs["pref_key_arrangement"] as? String)
            ?.takeUnless { prefs["pref_arrangement_auto_applied"] == true }
        val languages = (listOfNotNull(active) + enabled).distinct().mapNotNull { tag ->
            language(tag, if (tag == active) arrangement else null)
        }.distinctBy { it.layoutId }

        val dictionaries = (words.keys + phrases.keys).distinct().mapNotNull { lang ->
            val langId = LanguageRegistry.byLocale(lang)?.id ?: return@mapNotNull null
            val list = words[lang].orEmpty().map { (word, count) -> (capitals[lang to word] ?: word) to count }
            val pairs = phrases[lang].orEmpty()
            if (list.isEmpty() && pairs.isEmpty()) return@mapNotNull null
            ForeignDictionary(
                CustomDictionaries.ImportCandidate(
                    name = "$LEARNED_LIST_NAME $langId",
                    words = list,
                    ngrams = pairs,
                    shortcuts = emptyList(),
                    locale = lang,
                    description = null,
                ),
                langId,
                learned = true,
            )
        }

        val left = buildList {
            if (chords > 0) add(LeftBehind(LeftBehind.Kind.CHORDS, chords))
            if (baseLists > 0) add(LeftBehind(LeftBehind.Kind.BASE_DICTIONARIES, baseLists))
        }

        return ForeignBackup(
            keyboard = ForeignKeyboard.KINETICA,
            themes = listOfNotNull(theme),
            // Kinetica has one look, so it is the one that was in use.
            activeThemeRef = theme?.ref,
            languages = languages,
            settings = ForeignSettingsMap.map(ForeignKeyboard.KINETICA, prefs),
            dictionaries = dictionaries,
            blacklist = blocked.toList(),
            snippets = kineticaSnippets(expansions),
            leftBehind = left,
        )
    }

    /**
     * Kinetica's expansions as snippets. Kinetica replaces a trigger only when
     * asked, from its bar, so each one is offered as a chip here rather than
     * typed over the trigger. A trigger with several targets offers them all,
     * in Kinetica's order. A trigger the keyboard could not watch for here
     * (`^^`, `(-.-)'`) leaves the snippet in the list to insert by hand.
     */
    private fun kineticaSnippets(expansions: List<Triple<String, Int, String>>): List<Snippet> =
        expansions.groupBy { it.first }.map { (trigger, rows) ->
            val targets = rows.sortedBy { it.second }.map { it.third }.distinct()
            val first = targets.first()
            Snippet(
                id = 0,
                label = first.lineSequence().first().take(SNIPPET_LABEL_MAX),
                text = first,
                trigger = trigger.takeIf(SnippetMatcher::isMatchableTrigger),
                confirm = true,
                alternates = targets.drop(1).take(SnippetStore.MAX_ALTERNATES),
                multiExpand = if (targets.size > 1) MultiExpand.CHIPS_ONLY else MultiExpand.DEFAULT,
            )
        }

    /** Kinetica's `Backup.unesc`: `\\`, `\t`, `\n` and `\r`; any other escape keeps its backslash. */
    internal fun unescapeKinetica(text: String): String {
        if ('\\' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '\\' || i == text.length - 1) {
                out.append(c)
                i++
                continue
            }
            when (val next = text[i + 1]) {
                '\\' -> out.append('\\')
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                else -> out.append(c).append(next)
            }
            i += 2
        }
        return out.toString()
    }

    // ---- shared ----

    /**
     * The dictionaries among the kept entries, each read on its own (a version
     * 4 one as its header and body together) so one file this app cannot read
     * does not cost the others.
     */
    private fun dictionaries(archive: Archive, take: (String) -> Boolean): List<ForeignDictionary> {
        val files = archive.kept.filterKeys { take(it) && isDictionaryFile(it) }
        val groups = files.entries.groupBy { (name, _) ->
            name.removeSuffix(HEADER_SUFFIX).removeSuffix(BODY_SUFFIX)
        }
        return groups.flatMap { (stem, entries) ->
            val picked = entries.map { (name, bytes) -> CustomDictionaries.ImportFile(name, bytes) }
            val found = CustomDictionaries.inspect(picked) as? CustomDictionaries.Inspection.Found
                ?: return@flatMap emptyList()
            val learned = stem.contains(USER_HISTORY_NAME)
            found.dictionaries.map { candidate ->
                val langId = (candidate.locale ?: localeIn(stem))?.let(LanguageRegistry::byLocale)?.id ?: DEFAULT_LANG
                val named = if (learned) {
                    CustomDictionaries.ImportCandidate(
                        name = "$LEARNED_LIST_NAME $langId",
                        words = candidate.words,
                        ngrams = candidate.ngrams,
                        shortcuts = candidate.shortcuts,
                        locale = candidate.locale,
                        description = candidate.description,
                    )
                } else {
                    candidate
                }
                ForeignDictionary(named, langId, learned)
            }
        }
    }

    private fun isDictionaryFile(name: String): Boolean =
        name.endsWith(".dict") || name.endsWith(HEADER_SUFFIX) || name.endsWith(BODY_SUFFIX)

    /** `dicts/en_US/…` or `UserHistoryDictionary.en_US…`: the locale a path names. */
    private fun localeIn(path: String): String? {
        LOCALE_IN_PATH.find(path)?.let { return it.groupValues[1] }
        return null
    }

    /** A language and the built-in layout of its that goes by [layoutName], else its first. */
    internal fun language(tag: String, layoutName: String?): ForeignLanguage? {
        val language = LanguageRegistry.byLocale(tag) ?: return null
        val ids = language.layoutIds.takeIf { it.isNotEmpty() } ?: return null
        val wanted = layoutName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val id = wanted?.let { name -> ids.firstOrNull { it == "builtin_$name" || it.endsWith("_$name") } }
            ?: ids.first()
        return ForeignLanguage(language.id, id)
    }

    private fun leftCount(names: List<String>, kind: LeftBehind.Kind, test: (String) -> Boolean): LeftBehind? =
        names.count(test).takeIf { it > 0 }?.let { LeftBehind(kind, it) }

    private fun decodeBase36(text: String): String? = runCatching {
        BigInteger(text, BASE36).toByteArray().dropWhile { it == 0.toByte() }.toByteArray().decodeToString()
            .takeIf { decoded -> decoded.isNotBlank() && decoded.none { it == '�' || it.code < 0x20 } }
    }.getOrNull()

    private const val HELI_PREFS = "preferences.json"
    private const val HELI_PROTECTED = "protected_preferences.json"
    private const val LEAN_FLOATING = "floating_keyboard_preferences.json"
    private const val HELI_DICTS = "dicts/"
    private const val HELI_BLACKLISTS = "blacklists/"
    private const val USER_HISTORY = "UserHistoryDictionary"
    private const val USER_HISTORY_NAME = "UserHistoryDictionary"
    private val HELI_LAYOUT = Regex("""(?:unprotected/)?layouts/((?:[a-z_]+/)?)(custom\.[^/]+)""")
    private const val HELI_USER_COLORS = "user_colors_"
    private const val HELI_MORE_COLORS = "user_more_colors_"
    private const val HELI_ALL_COLORS = "user_all_colors_"
    private val HELI_THEME_PREFIXES = listOf(HELI_USER_COLORS, HELI_ALL_COLORS, HELI_MORE_COLORS)
    private const val ALL_COLOURS_MODE = 2
    private const val LAYOUT_SET = "KeyboardLayoutSet="
    private const val SCRIPT_CODE_LENGTH = 4

    private const val FUTO_VERSION = "FUTOKeyboardSettings_CfgExportVersion"
    private const val FUTO_DATASTORE = "datastore.preferences_pb"
    private const val FUTO_SHARED = "sharedPreferences.json"
    private const val FUTO_PERSONAL = "userdictionary.json"
    private const val FUTO_EXT = "ext/"
    private const val FUTO_USERDICT = "userdict/"
    private const val FUTO_LAYOUTS = "dev_customlayouts"
    private const val FUTO_BLACKLIST = "suggestionBlacklist"
    private const val FUTO_THEMES = "themes/"
    private const val FUTO_ACTIVE_THEME = "activeThemeOption"
    private const val FUTO_CUSTOM_THEME = "custom"

    private const val FLORIS_METADATA = "backup_metadata.json"
    private const val FLORIS_PREFS = "jetpref_datastore/florisboard-app-prefs.jetpref"
    private const val FLORIS_KEYBOARDS = "files/ime/keyboard/"
    private const val FLORIS_THEMES = "files/ime/theme/"
    private const val FLORIS_OWN_LAYOUTS = "org.florisboard.layouts"
    private val FLORIS_LAYOUT = Regex("""files/ime/keyboard/([^/]+)/layouts/characters/([^/]+)\.json""")
    private val FLORIS_THEME = Regex("""files/ime/theme/([^/]+)/.+""")

    private const val CLEVER_MANIFEST = "manifest.json"
    private const val CLEVER_CONFIG = "config.json"
    private const val CLEVER_LAYOUTS = "layouts"

    private const val KINETICA_THEME_REF = "kinetica"

    /** Kinetica suggests a learned word from its second use on (its `PERSONAL_MERGE_MIN_COUNT`). */
    private const val KINETICA_MIN_COUNT = 2
    private const val SNIPPET_LABEL_MAX = 60

    private val WANTED_NAMES = setOf(
        HELI_PREFS, HELI_PROTECTED, LEAN_FLOATING,
        FUTO_VERSION, FUTO_DATASTORE, FUTO_SHARED, FUTO_PERSONAL,
        FLORIS_METADATA, FLORIS_PREFS,
        CLEVER_MANIFEST, CLEVER_CONFIG,
    )
    private val WANTED_PREFIXES = listOf(
        "unprotected/layouts/", "layouts/", HELI_BLACKLISTS, HELI_DICTS,
        FUTO_USERDICT, FUTO_THEMES, FLORIS_KEYBOARDS, FLORIS_THEMES,
    )

    private const val HEADER_SUFFIX = ".header"
    private const val BODY_SUFFIX = ".body"
    private val LOCALE_IN_PATH = Regex("""(?:dicts/|UserHistoryDictionary[._]?)([a-z]{2,3}(?:[_-][A-Za-z]{2,4})?)""")
    private const val LEARNED_LIST_NAME = "Learned words"
    private const val DEFAULT_LANG = "en"
    private const val BASE36 = 36
    private const val BUFFER = 64 * 1024
}
