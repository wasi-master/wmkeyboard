package com.wasimaster.wmkeyboard.core.input.composer

import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Reading a FlorisBoard language pack.
 *
 * Every fixture is built here, table and archive both, from the published
 * format: a manifest field name and a table's three columns. Nothing is taken
 * from FlorisBoard's own packs, whose tables are their authors' work.
 *
 * Robolectric, because the pack is an SQLite database and reading one is an
 * Android API call. This is the module's only test that needs it.
 */
@RunWith(RobolectricTestRunner::class)
class FlexLanguagePackTest {

    private lateinit var filesDir: File
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        filesDir = File(context.filesDir, "flextest").apply { deleteRecursively(); mkdirs() }
        cacheDir = File(context.cacheDir, "flextest").apply { deleteRecursively(); mkdirs() }
    }

    /** A `.flex` holding one table of `(code, text, weight)` rows. */
    private fun pack(
        format: String = FlexLanguagePack.FORMAT,
        tables: Map<String, List<Triple<String, String, Int>>>,
    ): ByteArray {
        val database = File(cacheDir, "build.sqlite3").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(database, null).use { db ->
            for ((name, rows) in tables) {
                db.execSQL("create table $name(code VARCHAR(5), text TEXT, weight INT)")
                for ((code, text, weight) in rows) {
                    db.execSQL(
                        "insert into $name values(?, ?, ?)",
                        arrayOf<Any>(code, text, weight),
                    )
                }
            }
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("extension.json"))
            zip.write("""{"$":"$format","meta":{"id":"test"}}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("han.sqlite3"))
            zip.write(database.readBytes())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun import(bytes: ByteArray): FlexLanguagePack.Result =
        FlexLanguagePack.import(bytes.inputStream(), filesDir, cacheDir)

    private fun packLines(id: String): List<String> {
        val pack = checkNotNull(CjkDictCatalog.byId(id))
        return CjkDictStore.packFile(filesDir, pack).readLines()
    }

    @Test
    fun `a cangjie table fills the cangjie pack`() {
        val result = import(
            pack(
                tables = mapOf(
                    "cangjie5" to listOf(
                        Triple("a", "日", 100),
                        Triple("hqi", "我", 90),
                    ),
                ),
            ),
        )
        assertEquals(
            FlexLanguagePack.Result.Imported(packId = "cangjie", schema = "cangjie5", rows = 2),
            result,
        )
        assertEquals(listOf("a\t日\t100", "hqi\t我\t90"), packLines("cangjie"))
    }

    @Test
    fun `the imported pack is where a download would have put it`() {
        // Which is the whole point: the settings row, the state token that
        // makes a pack go live and deleting it again all work on the file, and
        // none of them needs to know it was imported.
        import(pack(tables = mapOf("cangjie5" to listOf(Triple("a", "日", 1)))))
        val pack = checkNotNull(CjkDictCatalog.byId("cangjie"))
        assertTrue(CjkDictStore.isDownloaded(filesDir, pack))
    }

    @Test
    fun `an imported table is one the dictionary reads`() {
        import(
            pack(
                tables = mapOf(
                    "cangjie5" to listOf(Triple("a", "日", 100), Triple("ab", "旦", 50)),
                ),
            ),
        )
        val table = CodeTableDictionary.parse(
            packLines("cangjie").asSequence(),
            CodeTableDictionary.CANGJIE_CODE,
        )
        assertEquals(listOf("日", "旦"), table.candidates("a"))
    }

    @Test
    fun `a stroke table written in letters becomes digits`() {
        // The source tables spell the strokes h s p n z. A stored code here is
        // always 1 to 5, so without the translation every row would be dropped
        // for having an invalid code.
        val result = import(
            pack(tables = mapOf("stroke5" to listOf(Triple("hs", "十", 10)))),
        )
        assertEquals("stroke", (result as FlexLanguagePack.Result.Imported).packId)
        assertEquals(listOf("12\t十\t10"), packLines("stroke"))
    }

    @Test
    fun `a row with a code this app cannot type is dropped`() {
        // `z` is not a Cangjie radical here, and a row under a code no
        // keystroke can produce would sit in the index unreachable.
        val result = import(
            pack(
                tables = mapOf(
                    "cangjie5" to listOf(Triple("a", "日", 1), Triple("zz", "?", 1)),
                ),
            ),
        )
        assertEquals(1, (result as FlexLanguagePack.Result.Imported).rows)
    }

    @Test
    fun `a fractional weight keeps its ranking`() {
        // The pack FlorisBoard ships stores weights as fractions, in a column
        // its own converter declares INT. Read as an integer every one of them
        // is 0, and `CodeTableDictionary` ranks a prefix's characters by
        // exactly this number, so a real pack imported with no ranking at all.
        val database = File(cacheDir, "weights.sqlite3").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(database, null).use { db ->
            db.execSQL("create table cangjie5(code VARCHAR(5), text TEXT, weight INT)")
            db.execSQL("insert into cangjie5 values('a', '日', 0.0625)")
            db.execSQL("insert into cangjie5 values('a', '曰', 0.03125)")
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("extension.json"))
            zip.write("""{"${'$'}":"${FlexLanguagePack.FORMAT}"}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("han.sqlite3"))
            zip.write(database.readBytes())
            zip.closeEntry()
        }
        assertTrue(import(out.toByteArray()) is FlexLanguagePack.Result.Imported)
        assertEquals(listOf("a\t日\t625", "a\t曰\t313"), packLines("cangjie"))
        // And the more common character leads its prefix, which is the point.
        val table = CodeTableDictionary.parse(
            packLines("cangjie").asSequence(),
            CodeTableDictionary.CANGJIE_CODE,
        )
        assertEquals(listOf("日", "曰"), table.candidates("a"))
    }

    @Test
    fun `an integer weight is left as it is`() {
        val result = import(pack(tables = mapOf("cangjie5" to listOf(Triple("a", "日", 4200)))))
        assertTrue(result is FlexLanguagePack.Result.Imported)
        assertEquals(listOf("a\t日\t4200"), packLines("cangjie"))
    }

    @Test
    fun `a pack of schemes this app cannot type names them back`() {
        val result = import(
            pack(
                tables = mapOf(
                    "wubi98" to listOf(Triple("aaaa", "工", 1)),
                    "zhengma" to listOf(Triple("bb", "二", 1)),
                ),
            ),
        )
        assertTrue(result is FlexLanguagePack.Result.NoUsableTable)
        assertEquals(
            listOf("wubi98", "zhengma"),
            (result as FlexLanguagePack.Result.NoUsableTable).schemas,
        )
    }

    @Test
    fun `a wubi 86 table fills the wubi pack`() {
        val result = import(
            pack(
                tables = mapOf(
                    "wubi86" to listOf(Triple("KHLG", "中国", 7), Triple("aaaaa", "长", 1), Triple("zzzy", "ㄤ", 1)),
                ),
            ),
        )
        assertEquals(FlexLanguagePack.Result.Imported("wubi", "wubi86", 1), result)
        val pack = CjkDictCatalog.byId("wubi")!!
        val dict = CjkDictStore.packFile(filesDir, pack).bufferedReader().useLines {
            CodeTableDictionary.parse(it, CodeTableDictionary.WUBI_CODE)
        }
        assertEquals(listOf("中国"), dict.candidates("khlg"))
    }

    @Test
    fun `a theme extension is not a language pack`() {
        val result = import(
            pack(
                format = "ime.extension.theme",
                tables = mapOf("cangjie5" to listOf(Triple("a", "日", 1))),
            ),
        )
        assertEquals(FlexLanguagePack.Result.NotALanguagePack, result)
    }

    @Test
    fun `anything else is unreadable`() {
        assertEquals(FlexLanguagePack.Result.Unreadable, import(ByteArray(0)))
        assertEquals(FlexLanguagePack.Result.Unreadable, import("not a zip".toByteArray()))
    }

    @Test
    fun `the tables can be listed without importing one`() {
        val bytes = pack(
            tables = mapOf(
                "cangjie5" to listOf(Triple("a", "日", 1)),
                "quick" to listOf(Triple("aa", "昌", 1)),
            ),
        )
        assertEquals(listOf("cangjie5", "quick"), FlexLanguagePack.schemas(bytes.inputStream(), cacheDir))
        val pack = checkNotNull(CjkDictCatalog.byId("cangjie"))
        assertTrue("listing installed something", !CjkDictStore.isDownloaded(filesDir, pack))
    }

    @Test
    fun `the unpacked database does not outlive the import`() {
        import(pack(tables = mapOf("cangjie5" to listOf(Triple("a", "日", 1)))))
        assertTrue(
            "left a database behind: ${cacheDir.list()?.toList()}",
            cacheDir.listFiles().orEmpty().none { it.name.startsWith("flex_languagepack") },
        )
    }
}
