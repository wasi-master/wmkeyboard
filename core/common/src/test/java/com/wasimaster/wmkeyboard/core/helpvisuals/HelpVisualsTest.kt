package com.wasimaster.wmkeyboard.core.helpvisuals

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The help picture index as the APK ships it, and the store's handling of packs. */
class HelpVisualsTest {

    @get:Rule val temp = TemporaryFolder()

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val offLight = "off, light".toByteArray()
    private val onLight = "on, light".toByteArray()
    private val offDark = "off, dark".toByteArray()
    private val onDark = "on, dark".toByteArray()
    private val clip = "clip".toByteArray()

    private fun indexText(): String = listOf(
        "# written by tools/helpvisuals/build.py",
        "wmhelp\t1",
        "pack\tlight\t${"a".repeat(64)}\t7000000",
        "pack\tdark\t${"b".repeat(64)}\t6900000",
        "visual\ttyping_chips_at_end_title\tcompare",
        "frame\tcommon_off\t720\t120\t${sha(offLight)}\t${offLight.size}\t${sha(offDark)}\t${offDark.size}",
        "frame\tcommon_on\t720\t120\t${sha(onLight)}\t${onLight.size}\t${sha(onDark)}\t${onDark.size}",
        "visual\tglide_typing_title\tclip",
        "frame\t-\t540\t400\t${sha(clip)}\t${clip.size}\t-\t0",
        "visual\tbroken_title\tstill",
        "frame\t-\t0\t0\tnot-a-sha\t1\t-\t0",
        "visual\tfrom_the_future_title\thologram",
        "frame\t-\t10\t10\t${sha(clip)}\t${clip.size}\t-\t0",
    ).joinToString("\n")

    @Test fun parsesVisualsFramesAndPacks() {
        val index = HelpVisualIndex.parse(indexText())
        assertEquals(setOf("typing_chips_at_end_title", "glide_typing_title"), index.visuals.keys)
        val compare = index.visuals.getValue("typing_chips_at_end_title")
        assertEquals(HelpVisualKind.COMPARE, compare.kind)
        assertEquals(listOf("common_off", "common_on"), compare.frames.map { it.label })
        assertEquals(6f, compare.frames[0].aspect, 0.001f)
        assertEquals(sha(onDark), compare.frames[1].file(HelpTheme.DARK)?.sha256)
        assertEquals((offLight.size + onLight.size).toLong(), compare.bytes(HelpTheme.LIGHT))
        assertEquals(7_000_000L, index.pack(HelpTheme.LIGHT)?.bytes)
        assertEquals("wmkb-help-dark-bbbbbbbbbbbb.zip", index.pack(HelpTheme.DARK)?.fileName)
    }

    @Test fun aFrameMayExistInOneLookOnly() {
        val frame = HelpVisualIndex.parse(indexText()).visuals.getValue("glide_typing_title").frames.single()
        assertNotNull(frame.file(HelpTheme.LIGHT))
        assertNull(frame.file(HelpTheme.DARK))
        assertNull(frame.label)
    }

    @Test fun anotherFormatVersionReadsAsNoPictures() {
        assertTrue(HelpVisualIndex.parse(indexText().replace("wmhelp\t1", "wmhelp\t2")).isEmpty)
        assertTrue(HelpVisualIndex.parse("").isEmpty)
    }

    @Test fun packNamesAreRecognised() {
        assertEquals(HelpTheme.LIGHT, HelpPack.themeOf("wmkb-help-light-0123456789ab.zip"))
        assertEquals(HelpTheme.DARK, HelpPack.themeOf(HelpPack.fileName(HelpTheme.DARK, "c".repeat(64))))
        assertNull(HelpPack.themeOf("wmkb-help-sepia-0123456789ab.zip"))
        assertNull(HelpPack.themeOf("wmkb-lang-bn.zip"))
    }

    private fun zip(name: String, entries: Map<String, ByteArray>): File {
        val file = File(temp.root, name)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((entry, bytes) in entries) {
                out.putNextEntry(ZipEntry(entry))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    @Test fun installsAPackAndKeepsOnlyFilesThatHashToTheirName() = runBlocking {
        val index = HelpVisualIndex.parse(indexText())
        val store = HelpVisualStore(File(temp.root, "store"), index) { error("no network in this test: $it") }
        val pack = zip(
            "wmkb-help-light-0123456789ab.zip",
            mapOf(
                "${sha(offLight)}.webp" to offLight,
                "${sha(onLight)}.webp" to onLight,
                // Named as one picture, holding another: dropped.
                "${sha(clip)}.webp" to "something else".toByteArray(),
                "../outside.webp" to offLight,
                "readme.txt" to "hello".toByteArray(),
            ),
        )
        assertFalse(store.packInstalled(HelpTheme.LIGHT))
        assertEquals(2, store.installPack(pack))
        assertTrue(store.packInstalled(HelpTheme.LIGHT))
        assertFalse(store.packInstalled(HelpTheme.DARK))
        val compare = index.visuals.getValue("typing_chips_at_end_title")
        assertTrue(store.complete(compare, HelpTheme.LIGHT))
        assertFalse(store.has(index.visuals.getValue("glide_typing_title").frames.single().file(HelpTheme.LIGHT)!!))
        assertFalse(File(temp.root, "outside.webp").exists())
        // Installing it again adds nothing.
        assertEquals(0, store.installPack(pack))
    }

    @Test fun theOtherLooksPictureStandsInUntilThisLooksArrives() = runBlocking {
        val index = HelpVisualIndex.parse(indexText())
        val store = HelpVisualStore(File(temp.root, "store"), index) { error("no network in this test: $it") }
        store.installPack(zip("wmkb-help-light-0123456789ab.zip", mapOf("${sha(offLight)}.webp" to offLight)))
        val frame = index.visuals.getValue("typing_chips_at_end_title").frames[0]
        val shown = store.resolve(frame, HelpTheme.DARK)
        assertEquals(HelpTheme.LIGHT, shown?.theme)
        assertNull(store.resolve(index.visuals.getValue("typing_chips_at_end_title").frames[1], HelpTheme.DARK))

        store.declinePack(HelpTheme.DARK)
        assertTrue(store.packDeclined(HelpTheme.DARK))
        store.installPack(zip("wmkb-help-dark-0123456789ab.zip", mapOf("${sha(offDark)}.webp" to offDark)))
        assertFalse(store.packDeclined(HelpTheme.DARK))
        assertEquals(HelpTheme.DARK, store.resolve(frame, HelpTheme.DARK)?.theme)
    }

    @Test fun aFileThatIsNotAPackIsRefused() = runBlocking {
        val store = HelpVisualStore(File(temp.root, "store"), HelpVisualIndex.EMPTY) { error(it) }
        assertNull(store.installPack(zip("pictures.zip", mapOf("a.webp" to offLight))))
    }

    @Test fun clearingForgetsThePacks() = runBlocking {
        val store = HelpVisualStore(File(temp.root, "store"), HelpVisualIndex.EMPTY) { error(it) }
        store.installPack(zip("wmkb-help-light-0123456789ab.zip", mapOf("${sha(offLight)}.webp" to offLight)))
        assertTrue(store.bytesOnDisk() > 0)
        store.clear()
        assertEquals(0L, store.bytesOnDisk())
        assertFalse(store.packInstalled(HelpTheme.LIGHT))
    }
}
