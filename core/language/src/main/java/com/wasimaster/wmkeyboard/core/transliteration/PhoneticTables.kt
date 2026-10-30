package com.wasimaster.wmkeyboard.core.transliteration

import java.io.OutputStream

/**
 * The phonetic languages whose index is a [PhoneticTable], and how each one's
 * table is filed and read (issue #716).
 *
 * The one place that says so, because two programs build these tables: the
 * keyboard, over whatever list is on the device, and the data repo's
 * `scripts/build_phonetic_indexes.sh`, which compiles this package and files
 * every published word list ahead of time so the keyboard can map the result
 * instead of folding a hundred thousand words at every start. Both go through
 * [file], so a prebuilt table is byte for byte what the device would have
 * built over the same list.
 *
 * Bangla's published table is filed over the download with the bundled list's
 * ranking laid over it ([BengaliPhoneticIndex.withBundledRanking]); that is
 * the caller's to do, here as on the device, and the entries handed in are
 * already ranked.
 */
object PhoneticTables {

    private enum class Fold(val id: Int, val revision: Int) {
        HINDI(HindiPhoneticIndex.FOLD, HindiPhoneticIndex.REVISION),
        URDU(UrduPhoneticIndex.FOLD, UrduPhoneticIndex.REVISION),
        BENGALI(BengaliPhoneticIndex.FOLD, BengaliPhoneticIndex.REVISION),
        ALPHABET(AlphabetPhoneticIndex.FOLD, AlphabetPhoneticIndex.REVISION),
    }

    private fun foldOf(languageId: String): Fold? = when {
        languageId == "hi" || IndicProfiles.forLanguage(languageId) != null -> Fold.HINDI
        languageId == "ur" || languageId == "fa" || languageId == "ar" -> Fold.URDU
        languageId == "bn" || languageId == "as" -> Fold.BENGALI
        languageId in MinorPhonetic.arabic -> Fold.URDU
        languageId in MinorPhonetic.languages -> Fold.ALPHABET
        else -> null
    }

    /** Whether [languageId]'s index is a table, and so can be prebuilt. */
    fun supports(languageId: String): Boolean = foldOf(languageId) != null

    /**
     * The name the data repo publishes [languageId]'s table under, beside its
     * word list. It carries the format version and the fold revision, so a
     * fold change publishes a new file rather than changing the bytes an
     * installed version already fetched.
     */
    fun publishedName(languageId: String): String {
        val fold = requireNotNull(foldOf(languageId)) { "$languageId has no phonetic table" }
        return "${languageId}_phonetic_v${PhoneticTableBuilder.VERSION}r${fold.revision}.wmpi.gz"
    }

    /**
     * Whether [table] was written by the fold this build files [languageId]
     * with, at this build's revision of it. A table from an older or newer
     * fold would answer with keys this build's lookups never ask for.
     */
    fun fits(languageId: String, table: PackedPhoneticTable): Boolean {
        val fold = foldOf(languageId) ?: return false
        return table.fold == fold.id && table.foldRevision == fold.revision
    }

    /** [languageId]'s table over [entries], built in memory. */
    fun build(languageId: String, entries: List<Pair<String, Int>>): PhoneticTable {
        val builder = builder(languageId)
        file(languageId, builder, entries)
        return builder.build()
    }

    /** [languageId]'s table over [entries], written to [out] for publishing. */
    fun write(languageId: String, entries: List<Pair<String, Int>>, out: OutputStream, sourceTag: String, sourceWords: Int) {
        val builder = builder(languageId)
        file(languageId, builder, entries)
        builder.write(out, sourceTag, sourceWords)
    }

    private fun builder(languageId: String): PhoneticTableBuilder {
        val fold = requireNotNull(foldOf(languageId)) { "$languageId has no phonetic table" }
        return PhoneticTableBuilder(fold.id, fold.revision)
    }

    private fun file(languageId: String, builder: PhoneticTableBuilder, entries: List<Pair<String, Int>>) {
        val profile = IndicProfiles.forLanguage(languageId)
        when {
            languageId == "hi" -> HindiPhoneticIndex.file(builder, entries)
            languageId == "ur" -> UrduPhoneticIndex.file(builder, entries)
            languageId == "bn" -> BengaliPhoneticIndex.file(builder, entries)
            languageId == "as" -> AssamesePhoneticIndex.file(builder, entries)
            profile != null -> {
                val prep = IndicProfiles.devanagariPrep(languageId)
                HindiPhoneticIndex.file(builder, boundedEntries(entries)) { prep(profile.script.toDevanagari(it)) }
            }
            languageId in MinorPhonetic.arabic ->
                UrduPhoneticIndex.file(builder, boundedEntries(entries), MinorPhonetic::urduSpelling)
            languageId in MinorPhonetic.languages -> AlphabetPhoneticIndex.file(languageId, builder, entries)
            // Persian and Arabic: the lists run to millions of words.
            else -> UrduPhoneticIndex.file(builder, boundedEntries(entries))
        }
    }

    /** [languageId]'s index over [table]. */
    fun index(languageId: String, table: PhoneticTable): PhoneticIndex {
        val profile = IndicProfiles.forLanguage(languageId)
        return when {
            languageId == "hi" -> HindiPhoneticIndex(table)
            languageId == "ur" -> UrduPhoneticIndex(table)
            languageId == "bn" -> BengaliPhoneticIndex(table)
            languageId == "as" -> AssamesePhoneticIndex(table)
            profile != null -> IndicPhoneticIndex(
                table = table,
                spellingOf = profile.script::toDevanagari,
                romanPrep = IndicProfiles.romanPrep(languageId),
                rerankBy = if (IndicProfiles.rerankFlatList(languageId)) IndicPhonetic(profile)::transliterate else null,
            )
            languageId == "fa" -> ArabicScriptPhoneticIndex(table, ArabicScriptPhoneticIndex::persianReadings)
            languageId == "ar" -> ArabicScriptPhoneticIndex(table, ArabicScriptPhoneticIndex::arabicReadings)
            languageId in MinorPhonetic.arabic -> ArabicScriptPhoneticIndex(table) { MinorPhonetic.readings(languageId, it) }
            languageId in MinorPhonetic.languages -> AlphabetPhoneticIndex(languageId, table)
            else -> error("$languageId has no phonetic table")
        }
    }
}
