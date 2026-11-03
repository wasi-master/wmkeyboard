package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceWireTest {

    private fun roundTrip(location: BackupLocation): BackupLocation =
        PlaceWire.decode(PlaceWire.encode(location)!!, id = "cafe0001")!!

    @Test
    fun `a server place crosses with its login`() {
        val sftp = BackupLocation(
            id = "00000002",
            type = BackupDestination.SFTP,
            name = "Home server",
            sftp = SftpConfig(
                host = "nas.lan",
                port = 2222,
                user = "me",
                privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----",
                keyPassphrase = "pass",
                path = "/backups",
                hostKey = "SHA256:abc",
            ),
        )
        val back = roundTrip(sftp)
        assertEquals("cafe0001", back.id)
        assertEquals(sftp.copy(id = "cafe0001"), back)
        assertTrue(back.configured)
    }

    @Test
    fun `logins a server only checks cross whole`() {
        val s3 = BackupLocation(
            id = "1",
            type = BackupDestination.S3,
            s3 = S3Config(
                endpoint = "https://s3.eu-central-003.backblazeb2.com",
                region = "eu-central-003",
                bucket = "b",
                prefix = "wm",
                accessKeyId = "id",
                secretAccessKey = "secret",
                pathStyle = true,
                preset = "b2",
                account = "x",
            ),
        )
        // The service picker is the sender's screen; the address it filled in is what crosses.
        assertEquals(s3.s3.copy(preset = "", account = ""), roundTrip(s3).s3)
        val git = BackupLocation(
            id = "2",
            type = BackupDestination.GIT,
            git = GitConfig(GitProvider.GITEA, server = "https://git.home", repository = "me/backups", token = "t", allowPublic = true),
        )
        assertEquals(git.git, roundTrip(git).git)
        val smb = BackupLocation(id = "3", type = BackupDestination.SMB, smb = SmbConfig("pc", 445, "share", "wm", "WG", "me", "pw", false))
        assertEquals(smb.smb, roundTrip(smb).smb)
    }

    @Test
    fun `no sign-in ever crosses`() {
        val places = listOf(
            BackupLocation(id = "1", type = BackupDestination.DROPBOX, refreshToken = "SECRET-dropbox"),
            BackupLocation(id = "2", type = BackupDestination.BOX, refreshToken = "SECRET-box", box = BoxConfig("Mine")),
            BackupLocation(
                id = "3",
                type = BackupDestination.PROTON,
                proton = ProtonConfig("me@proton.me", "SECRET-uid", "SECRET-access", "SECRET-refresh", "SECRET-salted", "Mine"),
            ),
            BackupLocation(id = "4", type = BackupDestination.PCLOUD, pcloud = PCloudConfig(PCloudRegion.EU, "me@x", "SECRET-auth", "Mine")),
            BackupLocation(id = "5", type = BackupDestination.MEGA, mega = MegaConfig("me@x", "SECRET-sid", "SECRET-master", "Mine")),
            BackupLocation(
                id = "6",
                type = BackupDestination.ICLOUD,
                icloud = ICloudConfig("me@icloud.com", "app-pw", "SECRET-trust", "SECRET-cookies", "SECRET-client", 5L, "Mine"),
            ),
            BackupLocation(
                id = "7",
                type = BackupDestination.SYNOLOGY,
                nas = NasConfig("nas.lan", "me", "pw", "/home/Drive", "SECRET-did", true, "pin"),
            ),
            BackupLocation(id = "8", type = BackupDestination.FOLDER, folderUri = "content://SECRET-tree"),
        )
        for (place in places) {
            val raw = PlaceWire.encode(place)!!
            assertFalse(raw, raw.contains("SECRET"))
        }
        // What does cross is what the person would otherwise type again.
        val proton = roundTrip(places[2])
        assertEquals("me@proton.me", proton.proton.user)
        assertEquals("Mine", proton.proton.folder)
        assertFalse(proton.configured)
        val pcloud = roundTrip(places[3]).pcloud
        assertEquals(PCloudRegion.EU, pcloud.region)
        assertEquals("me@x", pcloud.email)
        val icloud = roundTrip(places[5]).icloud
        assertEquals("app-pw", icloud.password)
        assertEquals("", icloud.trustToken)
        val nas = roundTrip(places[6]).nas
        assertEquals("pw", nas.password)
        assertEquals("pin", nas.certPin)
        assertEquals("", nas.deviceToken)
        assertEquals("", roundTrip(places[7]).folderUri)
    }

    @Test
    fun `each app's own default folder stays its own`() {
        val box = BackupLocation(id = "1", type = BackupDestination.BOX)
        assertFalse(PlaceWire.encode(box)!!.contains(BoxConfig.DEFAULT_FOLDER))
        // WM Pass names no folder for its own default, and this app reads that as its own.
        val fromPass = """{"v":1,"kind":"mega","user":"me@x","folder":""}"""
        assertEquals(MegaConfig.DEFAULT_FOLDER, PlaceWire.decode(fromPass)!!.mega.folder)
        val imap = """{"v":1,"kind":"imap","host":"imap.x","user":"me","password":"pw","security":"starttls"}"""
        val mail = PlaceWire.decode(imap)!!.imap
        assertEquals(ImapConfig.DEFAULT_MAILBOX, mail.mailbox)
        assertEquals(ImapSecurity.STARTTLS, mail.security)
        assertEquals(ImapSecurity.STARTTLS.defaultPort, mail.port)
    }

    @Test
    fun `what cannot be a place here is refused`() {
        assertNull(PlaceWire.encode(BackupLocation(id = "1", type = BackupDestination.NEARBY, nearbyDeviceId = "d")))
        assertNull(PlaceWire.decode("""{"v":1,"kind":"nearby"}"""))
        assertNull(PlaceWire.decode("""{"v":1,"kind":"carrier-pigeon"}"""))
        assertNull(PlaceWire.decode("not json"))
        assertNull(PlaceWire.decode("[1,2]"))
    }

    @Test
    fun `a newer sender's extra keys are ignored`() {
        val raw = """{"v":2,"kind":"webdav","name":"Cloud","url":"https://dav.x/","user":"me","password":"pw","colour":"teal"}"""
        val place = PlaceWire.decode(raw)!!
        assertEquals("Cloud", place.name)
        assertEquals("https://dav.x/", place.webDavUrl)
        assertTrue(place.configured)
    }
}
