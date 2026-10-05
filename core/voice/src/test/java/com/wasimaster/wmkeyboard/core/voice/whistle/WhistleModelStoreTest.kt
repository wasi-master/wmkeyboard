package com.wasimaster.wmkeyboard.core.voice.whistle

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhistleModelStoreTest {
    @Test fun recognizesACompleteSizedModelFile() {
        val root = createTempDir(prefix = "whistle-store-")
        try {
            val model = WhistleModelStore.modelFile(root)
            model.parentFile?.mkdirs()
            model.writeBytes(byteArrayOf(1, 2, 3))
            assertFalse(WhistleModelStore.isDownloaded(root))
            RandomAccessFile(model, "rw").use { it.setLength(WhistleModelStore.MODEL_BYTES) }
            assertTrue(WhistleModelStore.isDownloaded(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun installRejectsAFileWithAnInvalidChecksum() {
        val root = createTempDir(prefix = "whistle-install-")
        val source = File(root, "incoming.cact")
        try {
            RandomAccessFile(source, "rw").use { it.setLength(WhistleModelStore.MODEL_BYTES) }
            assertFalse(WhistleModelStore.install(root, source))
            assertFalse(WhistleModelStore.modelFile(root).exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
