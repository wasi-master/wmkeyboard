package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.settings.sync.SyncKeys
import com.wasimaster.wmkeyboard.core.settings.sync.SyncNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupLocationTest {

    @Test
    fun `a list of locations survives the store`() {
        val list = listOf(
            BackupLocation(id = "11111111", type = BackupDestination.DROPBOX, name = "Home", refreshToken = "tok"),
            BackupLocation(
                id = "22222222",
                type = BackupDestination.S3,
                enabled = false,
                s3 = S3Config(endpoint = "https://r2", bucket = "b", accessKeyId = "k", secretAccessKey = "s", pathStyle = true),
            ),
            BackupLocation(id = "33333333", type = BackupDestination.FTP, ftp = FtpConfig(host = "nas", port = 2121, user = "u", secure = false)),
            BackupLocation(id = "44444444", type = BackupDestination.WEBDAV, webDavUrl = "https://x/", webDavUser = "u", webDavPassword = "p"),
            BackupLocation(id = "55555555", type = BackupDestination.FOLDER, folderUri = "content://tree/1"),
        )
        assertEquals(list, BackupLocation.decodeList(BackupLocation.encodeList(list)))
    }

    @Test
    fun `garbage in the store is no locations, not a crash`() {
        assertEquals(emptyList<BackupLocation>(), BackupLocation.decodeList("not json"))
        assertEquals(emptyList<BackupLocation>(), BackupLocation.decodeList("""[{"type":"nope"}]"""))
    }

    @Test
    fun `the old single destination becomes one location, with a fixed id`() {
        val legacy = AutoBackupSettings(destination = BackupDestination.DROPBOX, dropboxRefreshToken = "tok")
        val location = BackupLocation.fromLegacy(legacy)!!
        assertEquals(BackupLocation.LEGACY_ID, location.id)
        assertEquals("tok", location.refreshToken)
        assertEquals(location, BackupLocation.fromLegacy(legacy))
        assertNull(BackupLocation.fromLegacy(AutoBackupSettings()))
    }

    @Test
    fun `sync goes through the ticked locations that can be tried`() {
        val a = BackupLocation(id = "aaaaaaaa", type = BackupDestination.DRIVE)
        val b = BackupLocation(id = "bbbbbbbb", type = BackupDestination.DRIVE)
        val unset = BackupLocation(id = "cccccccc", type = BackupDestination.DROPBOX)
        val all = listOf(a, b, unset)
        assertEquals(listOf(b), SyncSettings(locationIds = setOf("bbbbbbbb")).targets(all))
        assertEquals(listOf(a, b), SyncSettings(locationIds = setOf("aaaaaaaa", "bbbbbbbb", "cccccccc")).targets(all))
        assertEquals(emptyList<BackupLocation>(), SyncSettings(locationIds = setOf("gone")).targets(all))
    }

    @Test
    fun `backups go to the ticked locations only`() {
        val on = BackupLocation(id = "aaaaaaaa", type = BackupDestination.DRIVE)
        val off = BackupLocation(id = "bbbbbbbb", type = BackupDestination.DRIVE, backup = false)
        assertEquals(listOf(on), AutoBackupSettings(locations = listOf(on, off)).backupTargets)
        // The tick survives the store, and a location from before ticks defaults on.
        assertEquals(listOf(on, off), BackupLocation.decodeList(BackupLocation.encodeList(listOf(on, off))))
        assertTrue(BackupLocation.decodeList("""[{"id":"aaaaaaaa","type":"drive"}]""").single().backup)
    }

    @Test
    fun `sync file names parse, and are never backups`() {
        val name = SyncNaming.name("78c415cf", "OPPO Reno8 T", encrypted = true)
        assertEquals("wmkeyboard-sync_78c415cf_OPPO-Reno8-T.wmsync.enc", name)
        assertEquals("78c415cf", SyncNaming.installId(name))
        assertEquals("OPPO-Reno8-T", SyncNaming.device(name))
        assertEquals("78c415cf", SyncNaming.installId("wmkeyboard-sync_78c415cf_OPPO-Reno8-T (1).wmsync.json"))
        // What a folder provider actually did to a clash on a real phone.
        assertEquals("630c4870", SyncNaming.installId("wmkeyboard-sync_630c4870_OPPO-Reno8-T.wmsync (1).json"))
        assertFalse(SyncNaming.isOurs("wmkeyboard-sync_630c4870_x.txt"))
        assertFalse(com.wasimaster.wmkeyboard.core.settings.sink.AutoBackupNaming.isOurs(name))
        assertTrue(com.wasimaster.wmkeyboard.core.settings.sink.AutoBackupNaming.isListed(name))
    }

    @Test
    fun `per-device settings never sync`() {
        for (key in listOf("key_height", "key_height_landscape", "one_handed_mode", "floating_x", "hw_leader",
            "kde_hosts", "kde_clipboard_pauses", "lexicon_version", "sync_enabled", "auto_backup_locations", "number_row_landscape",
            "app_lock_enabled", "app_lock_secret", "app_lock_method", "custom_themes", "handwriting_onyx_pen")) {
            assertFalse(key, SyncKeys.syncable(key, includeSecrets = true))
        }
        for (key in listOf("autocorrect", "number_row", "number_row_corrections", "key_sound", "font_scale")) {
            assertTrue(key, SyncKeys.syncable(key, includeSecrets = false))
        }
        assertFalse(SyncKeys.syncable("ai_openai_key", includeSecrets = false))
        // Kept on this device when the user ticks the group, and only then.
        val toolbar = setOf(SyncKeys.LocalGroup.TOOLBAR)
        for (key in listOf("toolbar_tools", "toolbox_order", "tool_color_overrides")) {
            assertTrue(key, SyncKeys.syncable(key, includeSecrets = false))
            assertFalse(key, SyncKeys.syncable(key, includeSecrets = false, keepLocal = toolbar))
        }
        assertTrue(SyncKeys.syncable("enabled_layout_ids", includeSecrets = false, keepLocal = toolbar))
        assertFalse(
            SyncKeys.syncable("enabled_layout_ids", includeSecrets = false, keepLocal = setOf(SyncKeys.LocalGroup.LAYOUTS)),
        )
        assertTrue(SyncKeys.syncable("ai_openai_key", includeSecrets = true))
    }
}
