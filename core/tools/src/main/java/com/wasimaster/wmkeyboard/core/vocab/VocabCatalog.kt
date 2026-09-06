package com.wasimaster.wmkeyboard.core.vocab

/**
 * One downloadable vocabulary pack in the wmkeyboard-data repo
 * (https://github.com/wasi-master/wmkeyboard-data), under
 * `vocab/<langId>/<id>.wmvocab.json.gz`, with its translation sidecars at
 * `vocab/<langId>/<id>.tr.<code>.json.gz` for each of [translationCodes].
 *
 * Counts and sizes are display and progress hints, not checksums, so drift
 * against a newer repo is harmless.
 */
data class VocabCatalogEntry(
    val id: String,
    val name: String,
    val langId: String,
    /** The word list the pack was built from — the same id, for now. */
    val sourceId: String,
    val wordCount: Int,
    /** Compressed size of the pack — the progress denominator. */
    val approxGzBytes: Long,
    /** Languages a translation sidecar exists for. */
    val translationCodes: List<String> = emptyList(),
) {
    val url: String
        get() = "$BASE/$langId/$id.wmvocab.json.gz"

    fun translationUrl(code: String): String = "$BASE/$langId/$id.tr.$code.json.gz"

    private companion object {
        const val BASE = "https://raw.githubusercontent.com/wasi-master/wmkeyboard-data/HEAD/vocab"
    }
}

/**
 * The packs the data repo carries. Regenerate with
 * `tools/vocab/generate_catalog.py --data <path to a wmkeyboard-data checkout>`.
 */
object VocabCatalog {

    // GENERATED — do not edit by hand; run tools/vocab/generate_catalog.py.
    val entries: List<VocabCatalogEntry> = listOf(
        VocabCatalogEntry("b333", "Barron's 333", "en", "b333", 333, 0L),
        VocabCatalogEntry("ws1", "Word Smart 1", "en", "ws1", 850, 0L),
        VocabCatalogEntry("ws2", "Word Smart 2", "en", "ws2", 829, 0L),
    )
    // END GENERATED

    private val byId: Map<String, VocabCatalogEntry> = entries.associateBy { it.id }

    init {
        check(byId.size == entries.size) { "duplicate vocabulary catalog ids" }
    }

    fun byId(id: String): VocabCatalogEntry? = byId[id]

    fun forLanguage(langId: String): List<VocabCatalogEntry> = entries.filter { it.langId == langId }

    /** Every language the catalogue has a pack for. */
    val languages: List<String> get() = entries.map { it.langId }.distinct()
}
