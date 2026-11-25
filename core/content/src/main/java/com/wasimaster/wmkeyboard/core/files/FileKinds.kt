package com.wasimaster.wmkeyboard.core.files

import java.util.Locale

/** What a row of the file manager is, for its icon, its colour, the category it falls in and how it previews. */
enum class FileKind { FOLDER, IMAGE, VIDEO, AUDIO, PDF, DOC, SHEET, SLIDES, TEXT, CODE, ARCHIVE, APK, EBOOK, FONT, OTHER }

/**
 * Tells a file's [FileKind] from its type and its name. The type wins where
 * it is specific; a provider that calls everything a stream of bytes, or a
 * name nothing knows, falls back to the extension. Pure Kotlin, for tests.
 */
object FileKinds {

    /** The longest extension shown as a badge; a longer one is more likely part of the name than a type. */
    private const val BADGE_MAX = 4

    private val DOCS = setOf("doc", "docx", "odt", "rtf", "pages", "wpd")
    private val SHEETS = setOf("xls", "xlsx", "ods", "csv", "tsv", "numbers")
    private val SLIDES = setOf("ppt", "pptx", "odp", "key")
    private val TEXTS = setOf("txt", "md", "markdown", "log", "ini", "cfg", "conf", "srt", "vtt", "nfo", "rst", "tex", "env", "properties")
    private val CODES = setOf(
        "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "c", "h", "cc", "cpp", "hpp", "cs", "go", "rs", "swift",
        "rb", "php", "lua", "sh", "bash", "zsh", "fish", "ps1", "bat", "sql", "html", "htm", "xml", "svg", "css", "scss", "less",
        "json", "jsonc", "json5", "yaml", "yml", "toml", "gradle", "dart", "scala", "hs", "elm", "ex", "exs", "erl", "clj", "r",
        "pl", "vue", "svelte", "proto", "graphql", "dockerfile", "makefile", "cmake", "nix", "zig", "asm", "s",
    )
    private val ARCHIVES = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz4", "jar", "aar", "cab", "iso", "zipx")
    private val APKS = setOf("apk", "apks", "xapk", "apkm", "aab")
    private val EBOOKS = setOf("epub", "mobi", "azw", "azw3", "fb2", "djvu", "cbz", "cbr")
    private val FONTS = setOf("ttf", "otf", "woff", "woff2", "ttc")
    private val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif", "ico", "tif", "tiff", "dng", "raw")
    private val VIDEOS = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "wmv", "flv")
    private val AUDIOS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "amr", "mid", "midi")

    /** The extension of [name], lower case, or empty when it has none (a leading dot is a hidden file, not an extension). */
    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun of(entry: FileEntry): FileKind = if (entry.isDirectory) FileKind.FOLDER else of(entry.name, entry.mime)

    fun of(name: String, mime: String): FileKind {
        // A known extension says more than the type a phone guesses for it
        // (`.ts` is code far more often than a broadcast stream on a phone).
        byExtension(extensionOf(name))?.let { return it }
        val type = mime.lowercase(Locale.ROOT)
        return when {
            type.startsWith("image/") -> FileKind.IMAGE
            type.startsWith("video/") -> FileKind.VIDEO
            type.startsWith("audio/") -> FileKind.AUDIO
            type == "application/pdf" -> FileKind.PDF
            type == "application/vnd.android.package-archive" -> FileKind.APK
            type == "application/epub+zip" -> FileKind.EBOOK
            type.startsWith("font/") -> FileKind.FONT
            type.contains("spreadsheet") || type.contains("excel") || type == "text/csv" -> FileKind.SHEET
            type.contains("presentation") || type.contains("powerpoint") -> FileKind.SLIDES
            type.contains("wordprocessing") || type == "application/msword" || type == "application/rtf" -> FileKind.DOC
            type.contains("zip") || type.contains("compressed") || type.contains("x-tar") || type.contains("x-7z") ||
                type.contains("x-rar") || type.contains("gzip") -> FileKind.ARCHIVE
            type == "application/json" || type == "application/xml" || type == "application/javascript" -> FileKind.CODE
            type.startsWith("text/") -> FileKind.TEXT
            else -> FileKind.OTHER
        }
    }

    private fun byExtension(ext: String): FileKind? = when (ext) {
        "" -> null
        "pdf" -> FileKind.PDF
        in IMAGES -> FileKind.IMAGE
        in VIDEOS -> if (ext == "ts") FileKind.CODE else FileKind.VIDEO
        in AUDIOS -> FileKind.AUDIO
        in DOCS -> FileKind.DOC
        in SHEETS -> FileKind.SHEET
        in SLIDES -> FileKind.SLIDES
        in APKS -> FileKind.APK
        in ARCHIVES -> FileKind.ARCHIVE
        in EBOOKS -> FileKind.EBOOK
        in FONTS -> FileKind.FONT
        in CODES -> FileKind.CODE
        in TEXTS -> FileKind.TEXT
        else -> null
    }

    /** The short label on a file's icon: its extension in capitals, or nothing when it has none or it is too long to be one. */
    fun badgeOf(name: String): String {
        val ext = extensionOf(name)
        return if (ext.isEmpty() || ext.length > BADGE_MAX) "" else ext.uppercase(Locale.ROOT)
    }

    /** Whether the file is words a person reads, so it can be previewed, searched inside and typed into a field. */
    fun isTextual(kind: FileKind): Boolean = kind == FileKind.TEXT || kind == FileKind.CODE

    /** Whether a zip reader can list the file: the plain zip family, not the other archive formats. */
    fun isZip(name: String): Boolean = extensionOf(name) in setOf("zip", "jar", "aar", "apk", "zipx", "cbz", "epub")

    /** The language [com.wasimaster.wmkeyboard.core.tools.CodeHighlight] knows the file by, from its extension. */
    fun codeLanguage(name: String): String = when (val ext = extensionOf(name)) {
        "kts", "gradle" -> "kotlin"
        "kt" -> "kotlin"
        "py" -> "python"
        "js", "mjs", "cjs", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "rs" -> "rust"
        "rb" -> "ruby"
        "sh", "bash", "zsh", "fish" -> "bash"
        "yml" -> "yaml"
        "htm" -> "html"
        "md", "markdown" -> "markdown"
        "hs" -> "haskell"
        "cc", "cpp", "hpp" -> "cpp"
        "h" -> "c"
        "cs" -> "csharp"
        "ex", "exs" -> "elixir"
        else -> ext
    }
}
