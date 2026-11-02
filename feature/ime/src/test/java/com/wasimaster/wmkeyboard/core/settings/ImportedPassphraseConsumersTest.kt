package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.settings.sync.SyncFile
import com.wasimaster.wmkeyboard.core.settings.sync.SyncRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * An imported backup passphrase is what the two writers encrypt with (#940): each
 * one's output opens with it. The writers are private, so reflection reaches them.
 */
@RunWith(RobolectricTestRunner::class)
class ImportedPassphraseConsumersTest {

    private suspend fun importedSettings(): AutoBackupSettings {
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())
        repository.setAutoBackupEncrypt(true)
        repository.setAutoBackupPassphrase("secret")
        val bundle = repository.exportConfig(
            sections = setOf(ConfigBackup.Section.SETTINGS),
            includeSecrets = true,
            appVersion = 1,
            appVersionName = "test",
        )
        repository.setAutoBackupPassphrase("")
        repository.importConfig(bundle)
        return repository.settings.first().autoBackup
    }

    private fun opened(bytes: ByteArray): String {
        val result = BackupCrypto.decrypt(ByteArrayInputStream(bytes), "secret".toCharArray())
        return (result as BackupCrypto.DecryptResult.Ok).text
    }

    @Test
    fun `the auto backup's file opens with the imported passphrase`() = runBlocking {
        val auto = importedSettings()
        val stage = AutoBackupRunner::class.java.declaredMethods.single { it.name == "stage" }
            .apply { isAccessible = true }
        val write: suspend (OutputStream) -> Unit = { it.write("the bundle".toByteArray()) }
        val staged = suspendCoroutineUninterceptedOrReturn<File> { continuation ->
            stage.invoke(AutoBackupRunner, RuntimeEnvironment.getApplication(), auto, true, write, continuation)
        }
        assertEquals("the bundle", opened(staged.readBytes()))
    }

    @Test
    fun `sync's file opens with the imported passphrase`() = runBlocking {
        val auto = importedSettings()
        val file = SyncFile("install", "device", 1L, emptyMap())
        val encode = SyncRunner::class.java.getDeclaredMethod(
            "encodeFile", SyncFile::class.java, AutoBackupSettings::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val bytes = encode.invoke(SyncRunner, file, auto, true) as ByteArray
        assertNotEquals(SyncFile.encode(file), String(bytes))
        assertEquals(SyncFile.encode(file), opened(bytes))
    }
}
