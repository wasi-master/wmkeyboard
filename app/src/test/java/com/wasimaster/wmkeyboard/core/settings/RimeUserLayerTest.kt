package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.input.rime.RimeStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The backup carries what nothing can rebuild, and none of what a deploy can (#1072). */
class RimeUserLayerTest {

    private val filesDir: File = Files.createTempDirectory("rime-layer").toFile()

    private fun put(relative: String, bytes: ByteArray = byteArrayOf(1)) {
        File(RimeStore.root(filesDir), relative).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    @Test
    fun `the layer holds the user dictionaries and not the build cache`() {
        put("user/build/rime_ice.table.bin")
        put("user/build/t9.schema.yaml")
        put("user/rime_ice.userdb/CURRENT")
        put("user/rime_ice.userdb/LOCK", ByteArray(0))
        put("user/rime_ice.userdb/000003.log")
        put("user/installation.yaml")

        assertEquals(
            setOf("user/rime_ice.userdb/CURRENT", "user/rime_ice.userdb/000003.log", "user/installation.yaml"),
            RimeStore.userLayer(filesDir).toSet(),
        )
    }

    @Test
    fun `a restore skips an older backup's build cache`() {
        assertFalse(RimeStore.restoreUserLayer(filesDir, "user/build/rime_ice.table.bin", byteArrayOf(1)))
        assertFalse(File(RimeStore.root(filesDir), "user/build/rime_ice.table.bin").exists())
        assertTrue(RimeStore.restoreUserLayer(filesDir, "user/rime_ice.userdb/CURRENT", byteArrayOf(1)))
    }
}
