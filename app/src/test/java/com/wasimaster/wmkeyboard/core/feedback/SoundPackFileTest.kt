package com.wasimaster.wmkeyboard.core.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SoundPackFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(dir: File? = File(temp.root, "soundpacks")) =
        SoundPackStore(dir?.apply { mkdirs() })

    /** A minimal but real 16-bit mono PCM WAV, so the header sniff passes. */
    private fun wav(samples: Int = 64): ByteArray {
        val data = ByteArray(samples * 2)
        val out = ByteArrayOutputStream()
        fun le32(v: Int) = out.write(byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
        ))
        fun le16(v: Int) = out.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
        out.write("RIFF".toByteArray()); le32(36 + data.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
        le32(44100); le32(88200); le16(2); le16(16)
        out.write("data".toByteArray()); le32(data.size); out.write(data)
        return out.toByteArray()
    }

    private fun pack(manifest: String, files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(SoundPackFile.MANIFEST))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            for ((name, bytes) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun threeVariantPack(
        roles: String = "{}",
        press: String = """["sounds/1.wav","sounds/2.wav","sounds/3.wav"]""",
    ) = pack(
        """
        {"format":"wmkeyboard-sound-pack","version":1,"id":"test","name":"Test Pack",
         "author":"Nobody","packVersion":"1.2.3","gain":1.0,
         "press":$press,"release":[],"roles":$roles}
        """.trimIndent(),
        mapOf(
            "sounds/1.wav" to wav(),
            "sounds/2.wav" to wav(),
            "sounds/3.wav" to wav(),
            "sounds/space.wav" to wav(),
        ),
    )

    private fun import(s: SoundPackStore, bytes: ByteArray) =
        SoundPackFile.import(ByteArrayInputStream(bytes), s)

    private fun fixtureBytes(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("addons/$name")) {
            "missing test fixture addons/$name"
        }.use { it.readBytes() }

    // ---- the real thing ------------------------------------------------

    @Test
    fun `a pack built by the monkeytype repository's tooling imports`() {
        // The fixture is packs/click.wmsoundpack from
        // wasi-master/wmkeyboard-monkeytype-sounds, unmodified. The publishing
        // tooling and this importer are two halves of one format, written on
        // opposite sides of a repository boundary; without this test the only
        // thing checking they agree is a user's phone.
        val s = store()
        val result = import(s, fixtureBytes("click.wmsoundpack"))
        assertTrue("$result", result is SoundPackImportResult.Imported)
        val pack = (result as SoundPackImportResult.Imported).pack

        assertEquals("Click", pack.name)
        assertEquals("Monkeytype contributors", pack.author)
        assertEquals(3, pack.variantCount)
        // Monkeytype has no per-key sounds to import.
        assertTrue(pack.roles.isEmpty())

        val manifest = checkNotNull(s.manifestFor(pack.id))
        // Every role resolves to something playable, by falling back.
        for (role in KeySoundRole.entries) {
            val names = manifest.pressFor(role)
            assertEquals("$role", 3, names.size)
            for (name in names) assertNotNull("$role/$name", s.sampleFile(pack.id, name))
        }
        // A synthesized interface click is one event; there is no key-up.
        assertFalse(manifest.hasRelease())
        assertEquals(false, pack.hasRelease)
    }

    @Test
    fun `a pack cut into key-down and key-up imports both halves`() {
        // packs/nk-creams.wmsoundpack from the same repository — one of the
        // twelve sets whose recordings hold the switch coming back up, cut in
        // two at import time by its tooling. The other fixture only proves the
        // press half of the format still agrees across the boundary.
        val s = store()
        val result = import(s, fixtureBytes("nk-creams.wmsoundpack"))
        assertTrue("$result", result is SoundPackImportResult.Imported)
        val pack = (result as SoundPackImportResult.Imported).pack

        assertEquals(6, pack.variantCount)
        assertEquals(true, pack.hasRelease)
        // Six of each, stored as twelve distinct samples: no key-up is the
        // same file as a key-down.
        assertEquals(12, pack.sampleCount)

        val manifest = checkNotNull(s.manifestFor(pack.id))
        assertTrue(manifest.hasRelease())
        for (role in KeySoundRole.entries) {
            // The pack fills no roles, so every role falls back to both of the
            // pack's own lists rather than to the press one twice.
            val down = manifest.samplesFor(role, KeySoundPhase.PRESS)
            val up = manifest.samplesFor(role, KeySoundPhase.RELEASE)
            assertEquals("$role", 6, down.size)
            assertEquals("$role", 6, up.size)
            assertTrue("$role", down.none { it in up })
            for (name in down + up) assertNotNull("$role/$name", s.sampleFile(pack.id, name))
        }
    }

    @Test
    fun `a role that names only a press keeps the pack's key-up`() {
        val s = store()
        val result = import(
            s,
            pack(
                """
                {"format":"wmkeyboard-sound-pack","version":1,"id":"split","name":"Split",
                 "press":["sounds/1.wav"],"release":["sounds/1-up.wav"],
                 "roles":{"space":{"press":["sounds/space.wav"]}}}
                """.trimIndent(),
                mapOf(
                    "sounds/1.wav" to wav(),
                    "sounds/1-up.wav" to wav(32),
                    "sounds/space.wav" to wav(),
                ),
            ),
        )
        val pack = (result as SoundPackImportResult.Imported).pack
        val manifest = checkNotNull(s.manifestFor(pack.id))
        // The spacebar brought its own way down and nothing else, so it keeps
        // the board's key-up. Fallback is per field, not per role.
        assertEquals(
            manifest.pressFor(KeySoundRole.SPACE),
            manifest.samplesFor(KeySoundRole.SPACE, KeySoundPhase.PRESS),
        )
        assertNotEquals(
            manifest.pressFor(KeySoundRole.DEFAULT),
            manifest.pressFor(KeySoundRole.SPACE),
        )
        assertEquals(
            manifest.releaseFor(KeySoundRole.DEFAULT),
            manifest.releaseFor(KeySoundRole.SPACE),
        )
        assertEquals(true, pack.hasRelease)
    }

    @Test
    fun `a record written before hasRelease existed is backfilled from its manifest`() {
        val dir = File(temp.root, "soundpacks")
        val first = store(dir)
        val pack = (
            import(first, fixtureBytes("nk-creams.wmsoundpack"))
                as SoundPackImportResult.Imported
            ).pack
        assertEquals(true, pack.hasRelease)

        // Rewrite packs.json the way a build that predates the field wrote it:
        // the record is there, the field is not. Edited as text rather than
        // through the store, because the store has no way to express "absent".
        val index = File(dir, "packs.json")
        val aged = index.readText().replace(""""hasRelease":true,""", "")
        assertTrue("the field should have been in the file", aged != index.readText())
        index.writeText(aged)

        // A fresh store over the same directory answers it from the pack's own
        // manifest rather than reporting a pack with key-up samples as one
        // without — those packs were installed with their release lists intact
        // and have been playing them all along.
        assertEquals(true, SoundPackStore(dir).pack(pack.id)?.hasRelease)
        // And the answer is written back, so it is read once and not again.
        assertTrue(index.readText().contains(""""hasRelease":true"""))
    }

    // ---- accepting -----------------------------------------------------

    @Test
    fun `a three-variant pack imports with all three on disk`() {
        val s = store()
        val result = import(s, threeVariantPack())
        assertTrue("$result", result is SoundPackImportResult.Imported)
        val pack = (result as SoundPackImportResult.Imported).pack
        assertEquals("Test Pack", pack.name)
        assertEquals(3, pack.variantCount)
        // The fourth file is in the archive but nothing references it, so it
        // is not stored: a pack ships what it plays.
        assertEquals(3, pack.sampleCount)

        val manifest = checkNotNull(s.manifestFor(pack.id))
        assertEquals(3, manifest.press.size)
        for (name in manifest.press) assertNotNull(s.sampleFile(pack.id, name))
    }

    @Test
    fun `a role falls back to the default set per field`() {
        val s = store()
        val result = import(
            s,
            threeVariantPack(roles = """{"space":{"press":["sounds/space.wav"],"gain":0.5}}"""),
        )
        val pack = (result as SoundPackImportResult.Imported).pack
        assertEquals(listOf("space"), pack.roles)

        val manifest = checkNotNull(s.manifestFor(pack.id))
        assertEquals(1, manifest.pressFor(KeySoundRole.SPACE).size)
        // Enter filled nothing, so it plays the pack's own three.
        assertEquals(3, manifest.pressFor(KeySoundRole.ENTER).size)
        assertEquals(0.5f, manifest.gainFor(KeySoundRole.SPACE), 0.001f)
        assertEquals(1f, manifest.gainFor(KeySoundRole.ENTER), 0.001f)
    }

    // ---- per-key sets (issue #520) -------------------------------------

    @Test
    fun `a named key beats its role and the board, per field`() {
        val s = store()
        val result = import(
            s,
            pack(
                """
                {"format":"wmkeyboard-sound-pack","version":1,"id":"voice","name":"Voice",
                 "press":["sounds/1.wav"],"release":["sounds/1-up.wav"],
                 "roles":{"space":{"press":["sounds/space.wav"]}},
                 "keys":{"a":{"press":["sounds/a.wav","sounds/a2.wav"],"gain":0.5},
                         "th":{"press":["sounds/th.wav"]}}}
                """.trimIndent(),
                mapOf(
                    "sounds/1.wav" to wav(),
                    "sounds/1-up.wav" to wav(32),
                    "sounds/space.wav" to wav(),
                    "sounds/a.wav" to wav(),
                    "sounds/a2.wav" to wav(),
                    "sounds/th.wav" to wav(),
                ),
            ),
        )
        val pack = (result as SoundPackImportResult.Imported).pack
        assertEquals(2, pack.keyCount)

        val manifest = checkNotNull(s.manifestFor(pack.id))
        val a = KeySoundTarget(KeySoundRole.DEFAULT, "a")
        assertEquals(2, manifest.samplesFor(a, KeySoundPhase.PRESS).size)
        // The key named only a way down, so it keeps the board's key-up: the
        // fallback is per field here exactly as it is for a role.
        assertEquals(
            manifest.releaseFor(KeySoundRole.DEFAULT),
            manifest.samplesFor(a, KeySoundPhase.RELEASE),
        )
        assertEquals(0.5f, manifest.gainFor(a), 0.001f)

        // A key the pack never named is untouched by the fact that others were.
        val z = KeySoundTarget(KeySoundRole.DEFAULT, "z")
        assertEquals(manifest.press, manifest.samplesFor(z, KeySoundPhase.PRESS))
        assertEquals(1f, manifest.gainFor(z), 0.001f)

        // A multi-character key is one entry, which is the point of keying on
        // what a key types rather than on a key code.
        assertEquals(
            1,
            manifest.samplesFor(KeySoundTarget(KeySoundRole.DEFAULT, "th"), KeySoundPhase.PRESS).size,
        )

        // Every sample of every set is reachable for the player to decode.
        val all = manifest.allSamples()
        assertEquals(all.size, all.distinct().size)
        for (name in all) assertNotNull(name, s.sampleFile(pack.id, name))
    }

    @Test
    fun `a key name is lowercased at import so a shifted letter resolves`() {
        val s = store()
        val result = import(
            s,
            pack(
                """
                {"format":"wmkeyboard-sound-pack","version":1,"id":"caps","name":"Caps",
                 "press":["sounds/1.wav"],"keys":{"A":{"press":["sounds/a.wav"]}}}
                """.trimIndent(),
                mapOf("sounds/1.wav" to wav(), "sounds/a.wav" to wav()),
            ),
        )
        val pack = (result as SoundPackImportResult.Imported).pack
        val manifest = checkNotNull(s.manifestFor(pack.id))
        // The keyboard lowercases what a key types before asking, so a pack
        // that shipped "A" has to be found by "a" or the entry is dead weight.
        assertEquals(
            1,
            manifest.keySamplesFor(KeySoundTarget(KeySoundRole.DEFAULT, "a"), KeySoundPhase.PRESS).size,
        )
    }

    @Test
    fun `a key set with a key-up makes the pack a key-up pack`() {
        val s = store()
        val result = import(
            s,
            pack(
                """
                {"format":"wmkeyboard-sound-pack","version":1,"id":"up","name":"Up",
                 "press":["sounds/1.wav"],
                 "keys":{"a":{"press":["sounds/a.wav"],"release":["sounds/a-up.wav"]}}}
                """.trimIndent(),
                mapOf("sounds/1.wav" to wav(), "sounds/a.wav" to wav(), "sounds/a-up.wav" to wav(32)),
            ),
        )
        val pack = (result as SoundPackImportResult.Imported).pack
        // The release toggle is drawn off this flag, and a pack whose only
        // key-up is on one letter still has one.
        assertEquals(true, pack.hasRelease)
        assertEquals(true, checkNotNull(s.manifestFor(pack.id)).hasRelease())
    }

    @Test
    fun `a per-key pack past the old sixty-four file ceiling imports whole`() {
        // The ceiling issue #520 asked about: a voice pack is one recording per
        // letter, which the old limit of 64 files could not hold with variants.
        val letters = ('a'..'z').toList()
        val keys = letters.joinToString(",") { c ->
            """"$c":{"press":["sounds/$c-1.wav","sounds/$c-2.wav","sounds/$c-3.wav"]}"""
        }
        val files = buildMap {
            put("sounds/1.wav", wav())
            for (c in letters) for (n in 1..3) put("sounds/$c-$n.wav", wav())
        }
        val s = store()
        val result = import(
            s,
            pack(
                """
                {"format":"wmkeyboard-sound-pack","version":1,"id":"animalese","name":"Animalese",
                 "press":["sounds/1.wav"],"keys":{$keys}}
                """.trimIndent(),
                files,
            ),
        )
        val pack = (result as SoundPackImportResult.Imported).pack
        assertEquals(26, pack.keyCount)
        // 26 letters x 3 takes, plus the board-wide fallback: every one stored.
        assertEquals(79, pack.sampleCount)
        val manifest = checkNotNull(s.manifestFor(pack.id))
        for (c in letters) {
            val target = KeySoundTarget(KeySoundRole.DEFAULT, c.toString())
            assertEquals("$c", 3, manifest.samplesFor(target, KeySoundPhase.PRESS).size)
        }
    }

    @Test
    fun `a pack with only per-key sounds is accepted and counted`() {
        val s = store()
        val result = import(
            s,
            pack(
                """
                {"format":"wmkeyboard-sound-pack","version":1,"id":"only","name":"Only keys",
                 "keys":{"a":{"press":["sounds/a.wav","sounds/a2.wav"]},"b":{"press":["sounds/b.wav"]}}}
                """.trimIndent(),
                mapOf("sounds/a.wav" to wav(), "sounds/a2.wav" to wav(), "sounds/b.wav" to wav()),
            ),
        )
        assertTrue("$result", result is SoundPackImportResult.Imported)
        val pack = (result as SoundPackImportResult.Imported).pack
        // The row counts recordings per key press, so the longest set it named
        // rather than the top-level list it does not have.
        assertEquals(2, pack.variantCount)
        assertEquals(2, pack.keyCount)

        val manifest = checkNotNull(s.manifestFor(pack.id))
        // A key it never named has nothing to play, and the player answers that
        // with the system click rather than with silence.
        assertTrue(
            manifest.samplesFor(KeySoundTarget(KeySoundRole.DEFAULT, "z"), KeySoundPhase.PRESS)
                .isEmpty(),
        )
    }

    @Test
    fun `a pack with no playable key-down anywhere is refused`() {
        val s = store()
        val result = import(
            s,
            pack(
                """
                {"format":"wmkeyboard-sound-pack","version":1,"id":"empty","name":"Empty",
                 "keys":{"a":{"press":["sounds/gone.wav"]}}}
                """.trimIndent(),
                mapOf("sounds/other.wav" to wav()),
            ),
        )
        assertTrue("$result", result is SoundPackImportResult.Rejected)
    }

    @Test
    fun `gain is clamped into what SoundPool can actually do`() {
        val s = store()
        val result = import(
            s,
            pack(
                """
                {"format":"wmkeyboard-sound-pack","version":1,"id":"loud","name":"Loud",
                 "gain":4.0,"press":["sounds/1.wav"],"roles":{"space":{"gain":-1.0}}}
                """.trimIndent(),
                mapOf("sounds/1.wav" to wav()),
            ),
        )
        val pack = (result as SoundPackImportResult.Imported).pack
        val manifest = checkNotNull(s.manifestFor(pack.id))
        // SoundPool.play takes 0..1 and cannot amplify, so a pack asking for
        // 4.0 gets 1.0 rather than a field that silently does nothing.
        assertEquals(1f, manifest.gainFor(KeySoundRole.DEFAULT), 0.001f)
        assertEquals(0f, manifest.gainFor(KeySoundRole.SPACE), 0.001f)
    }

    @Test
    fun `a missing variant is dropped and the rest of the pack survives`() {
        val s = store()
        val result = import(
            s,
            threeVariantPack(press = """["sounds/1.wav","sounds/gone.wav","sounds/3.wav"]"""),
        )
        val pack = (result as SoundPackImportResult.Imported).pack
        // Losing one of three recordings is not worth refusing the other two.
        assertEquals(2, pack.variantCount)
    }

    // ---- refusing ------------------------------------------------------

    @Test
    fun `an archive with no manifest is not a sound pack`() {
        val s = store()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("sounds/1.wav"))
            zip.write(wav())
            zip.closeEntry()
        }
        assertEquals(SoundPackImportResult.NotASoundPack, import(s, out.toByteArray()))
    }

    @Test
    fun `a manifest for another format is refused`() {
        val s = store()
        val bytes = pack("""{"format":"mechvibes","press":["sounds/1.wav"]}""", mapOf("sounds/1.wav" to wav()))
        assertEquals(SoundPackImportResult.NotASoundPack, import(s, bytes))
    }

    @Test
    fun `a newer format version is refused rather than guessed at`() {
        val s = store()
        val bytes = pack(
            """{"format":"wmkeyboard-sound-pack","version":2,"id":"x","name":"X","press":["sounds/1.wav"]}""",
            mapOf("sounds/1.wav" to wav()),
        )
        val result = import(s, bytes)
        assertTrue("$result", result is SoundPackImportResult.Rejected)
        assertEquals("2", (result as SoundPackImportResult.Rejected).messageArg)
    }

    @Test
    fun `a pack whose samples are all unplayable is refused`() {
        val s = store()
        val bytes = pack(
            """{"format":"wmkeyboard-sound-pack","version":1,"id":"x","name":"X","press":["sounds/1.wav"]}""",
            mapOf("sounds/1.wav" to "<!DOCTYPE html><html>nope</html>".toByteArray()),
        )
        val result = import(s, bytes)
        assertTrue("$result", result is SoundPackImportResult.Rejected)
    }

    // ---- entry names are never paths -----------------------------------

    @Test
    fun `a traversing entry name cannot write outside the store`() {
        val s = store()
        val escape = File(temp.root, "escaped.wav")
        val bytes = pack(
            """
            {"format":"wmkeyboard-sound-pack","version":1,"id":"evil","name":"Evil",
             "press":["../../escaped.wav"]}
            """.trimIndent(),
            mapOf("../../escaped.wav" to wav()),
        )
        val result = import(s, bytes)

        // The entry resolves — it is a map key, not a path — so the pack
        // installs, but the byte array lands in a file this importer named.
        assertTrue("$result", result is SoundPackImportResult.Imported)
        assertTrue("the importer wrote outside its own directory", !escape.exists())
        val pack = (result as SoundPackImportResult.Imported).pack
        assertEquals(listOf("s000.snd"), checkNotNull(s.manifestFor(pack.id)).press)
    }

    @Test
    fun `a sample name the store did not generate resolves to nothing`() {
        val s = store()
        val pack = (import(s, threeVariantPack()) as SoundPackImportResult.Imported).pack
        // The assertion that makes joining an on-disk manifest string onto a
        // directory safe at play time.
        assertNull(s.sampleFile(pack.id, "../../../etc/passwd"))
        assertNull(s.sampleFile(pack.id, "pack.json"))
        assertNotNull(s.sampleFile(pack.id, "s000.snd"))
    }

    // ---- store ---------------------------------------------------------

    private fun leanTypePack(
        sounds: String = """
            "keypress.default":{"files":["audio/1.ogg","audio/2.ogg","audio/3.ogg"],"mode":"random","volume":0.5},
            "keypress.space":{"files":["audio/space.ogg","audio/unused.ogg"],"mode":"single","volume":1.0},
            "keypress.return":{"files":["audio/return.ogg"],"mode":"cycle"},
            "keypress.shift":{"files":["audio/shift.ogg"],"mode":"single","volume":0.5},
            "keypress.symbol":{"files":["audio/symbol.ogg"],"mode":"single","volume":1.0}
        """.trimIndent(),
        schemaVersion: Int = 1,
    ) = pack(
        """
        {"schemaVersion":$schemaVersion,"id":"dev.leantype.sounds.test","name":"Lean Pack",
         "summary":"A test pack","versionCode":3,"versionName":"2.1.0","author":"Lab",
         "license":"CC0-1.0","defaultMasterVolume":0.8,"preview":"audio/1.ogg","tags":["x"],
         "sounds":{$sounds}}
        """.trimIndent(),
        listOf("1", "2", "3", "space", "unused", "return", "shift", "symbol")
            .associate { "audio/$it.ogg" to wav() },
    )

    @Test
    fun `a LeanType pack imports with its details`() {
        val s = store()
        val pack = (import(s, leanTypePack()) as SoundPackImportResult.Imported).pack

        assertEquals("Lean Pack", pack.name)
        assertEquals("Lab", pack.author)
        assertEquals("2.1.0", pack.version)
        assertEquals(3, pack.variantCount)
        assertEquals(
            listOf(KeySoundRole.SPACE, KeySoundRole.ENTER, KeySoundRole.MODIFIER).map { it.serialName },
            pack.roles,
        )
        assertEquals("A test pack", checkNotNull(s.manifestFor(pack.id)).description)
    }

    @Test
    fun `a LeanType pack keeps the files its modes play`() {
        val s = store()
        val pack = (import(s, leanTypePack()) as SoundPackImportResult.Imported).pack
        val manifest = checkNotNull(s.manifestFor(pack.id))

        assertEquals(3, manifest.pressFor(KeySoundRole.DEFAULT).size)
        // "single" plays the first file; the second is never stored.
        assertEquals(1, manifest.pressFor(KeySoundRole.SPACE).size)
        assertEquals(7, pack.sampleCount)
        assertEquals(1, manifest.pressFor(KeySoundRole.ENTER).size)
        // Shift and symbol both land in the one modifier role.
        assertEquals(2, manifest.pressFor(KeySoundRole.MODIFIER).size)
        // An event the pack leaves out falls back, as LeanType's does.
        assertEquals(manifest.press, manifest.pressFor(KeySoundRole.DELETE))
        assertFalse(manifest.hasRelease())
    }

    @Test
    fun `a LeanType pack multiplies its master volume into each gain`() {
        val s = store()
        val pack = (import(s, leanTypePack()) as SoundPackImportResult.Imported).pack
        val manifest = checkNotNull(s.manifestFor(pack.id))

        assertEquals(0.4f, manifest.gainFor(KeySoundRole.DEFAULT), 0.001f)
        assertEquals(0.8f, manifest.gainFor(KeySoundRole.SPACE), 0.001f)
        // No volume on the event means full volume, not the default event's.
        assertEquals(0.8f, manifest.gainFor(KeySoundRole.ENTER), 0.001f)
        assertEquals(0.4f, manifest.gainFor(KeySoundRole.MODIFIER), 0.001f)
        assertEquals(0.4f, manifest.gainFor(KeySoundRole.DELETE), 0.001f)
    }

    @Test
    fun `a LeanType pack from a later schema is refused`() {
        val result = import(store(), leanTypePack(schemaVersion = 2))
        assertTrue("$result", result is SoundPackImportResult.Rejected)
    }

    @Test
    fun `a LeanType pack with no default sound is refused`() {
        val result = import(
            store(),
            leanTypePack(
                """
                "keypress.default":{"files":[]},
                "keypress.space":{"files":["audio/space.ogg"]}
                """.trimIndent(),
            ),
        )
        assertTrue("$result", result is SoundPackImportResult.Rejected)
    }

    @Test
    fun `the name schemaVersion alone is not a LeanType pack`() {
        val result = import(
            store(),
            pack("""{"schemaVersion":1,"name":"Something else"}""", mapOf("a.wav" to wav())),
        )
        assertEquals(SoundPackImportResult.NotASoundPack, result)
        assertFalse(SoundPackFile.recognises("""{"schemaVersion":1,"name":"Something else"}"""))
        assertTrue(SoundPackFile.recognises("""{"schemaVersion":1,"sounds":{"keypress.default":{}}}"""))
    }

    @Test
    fun `deleting a pack takes its directory with it`() {
        val s = store()
        val pack = (import(s, threeVariantPack()) as SoundPackImportResult.Imported).pack
        val dir = checkNotNull(s.existingDirFor(pack.id))
        s.delete(pack.id)
        assertTrue("the pack directory outlived the record", !dir.exists())
        assertNull(s.pack(pack.id))
    }

    @Test
    fun `packs round-trip through disk`() {
        val dir = File(temp.root, "soundpacks")
        val first = store(dir)
        val pack = (import(first, threeVariantPack()) as SoundPackImportResult.Imported).pack

        val second = SoundPackStore(dir)
        assertEquals(1, second.packs().size)
        assertEquals("Test Pack", second.pack(pack.id)?.name)
        assertEquals(pack.id, second.resolve("Test Pack"))
    }

    @Test
    fun `with no directory the store reads as empty and writes nothing`() {
        val s = store(dir = null)
        assertTrue(s.packs().isEmpty())
        assertEquals(SoundPackImportResult.Failed, import(s, threeVariantPack()))
        assertTrue("nothing should have been written", temp.root.listFiles().orEmpty().isEmpty())
    }
}
