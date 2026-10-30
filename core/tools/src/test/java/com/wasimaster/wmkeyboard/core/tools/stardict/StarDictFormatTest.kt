package com.wasimaster.wmkeyboard.core.tools.stardict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Base64

class StarDictFormatTest {

    @Test
    fun `an ifo reads its fields and anything else is refused`() {
        val ifo = StarDictFormat.parseIfo(
            "StarDict's dict ifo file\nversion=3.0.0\nbookname=Test = Book\nwordcount=2\nidxoffsetbits=64\nsametypesequence=h\n",
        )!!
        assertEquals("Test = Book", ifo.bookName)
        assertEquals(64, ifo.offsetBits)
        assertEquals("h", ifo.sameTypeSequence)
        assertNull(StarDictFormat.parseIfo("bookname=No magic"))
        assertNull(StarDictFormat.parseIfo("StarDict's dict ifo file\nversion=2.4.2"))
    }

    @Test
    fun `an index reads 32 and 64 bit offsets`() {
        fun index(wide: Boolean) = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).run {
                write("héllo".toByteArray())
                writeByte(0)
                if (wide) writeLong(1L shl 33) else writeInt(-1)
                writeInt(7)
            }
        }.toByteArray()
        val rows = ArrayList<StarDictFormat.IndexRow>()
        StarDictFormat.forEachIndexRow(ByteArrayInputStream(index(false)), 32) { rows += it }
        StarDictFormat.forEachIndexRow(ByteArrayInputStream(index(true)), 64) { rows += it }
        assertEquals("héllo", rows[0].word)
        assertEquals(0xFFFFFFFFL, rows[0].offset)
        assertEquals(1L shl 33, rows[1].offset)
        assertEquals(7, rows[1].size)
    }

    @Test
    fun `a same type sequence stores no types and its last part runs to the end`() {
        val record = "fone".toByteArray() + 0 + "a <b>sound</b>".toByteArray()
        assertEquals("/fone/\na sound", StarDictFormat.definition(record, "th"))
    }

    @Test
    fun `without a sequence each part names its type, and a picture is skipped`() {
        val out = ByteArrayOutputStream()
        out.write('m'.code)
        out.write("plain".toByteArray())
        out.write(0)
        out.write('P'.code)
        DataOutputStream(out).writeInt(3)
        out.write(byteArrayOf(1, 2, 3))
        out.write('m'.code)
        out.write("more".toByteArray())
        out.write(0)
        assertEquals("plain\nmore", StarDictFormat.definition(out.toByteArray(), null))
    }

    @Test
    fun `a damaged record ends instead of reading past itself`() {
        assertEquals("", StarDictFormat.definition(byteArrayOf('P'.code.toByte(), 0x7F, 0, 0, 0, 1), null))
        assertEquals("cut", StarDictFormat.definition("mcut".toByteArray(), null))
    }

    @Test
    fun `markup becomes lines and entities are decoded`() {
        assertEquals(
            "one & two\nthree é",
            StarDictFormat.stripMarkup("<style>x{}</style><div>one &amp; two<br/>three &#233;</div><!-- no -->"),
        )
    }

    @Test
    fun `only a whole book counts, whatever folder it is in`() {
        assertTrue(StarDictArchive.holdsBook(listOf("a/x.ifo", "a/x.idx.gz", "a/x.dict.dz")))
        assertFalse(StarDictArchive.holdsBook(listOf("x.ifo", "x.idx")))
        assertTrue(StarDictArchive.isTarName("book.tar.bz2"))
        assertFalse(StarDictArchive.isTarName("book.zip"))
    }

    /** `dict/a.ifo` = "hello" and `dict/a.dict` = 100,000 bytes of a pattern, tarred and bzip2ed by the real tools. */
    @Test
    fun `a real tar bz2 unpacks flat with its bytes intact`() {
        val dir = File.createTempFile("stardict", "").apply { delete(); mkdirs() }
        try {
            val archive = File(dir, "a.tar.bz2").apply { writeBytes(Base64.getDecoder().decode(FIXTURE_TAR_BZ2)) }
            val out = File(dir, "out")
            val names = StarDictArchive.unpack(archive, out)
            assertEquals(setOf("a.ifo", "a.dict"), names.toSet())
            assertEquals("hello", File(out, "a.ifo").readText())
            val body = File(out, "a.dict").readBytes()
            assertEquals(100_000, body.size)
            val intact = body.indices.all { i ->
                body[i] == if (i % 1000 < 100) 7.toByte() else (i.toLong() * i % 251).toInt().toByte()
            }
            assertTrue(intact)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `junk is not unpacked as a book`() {
        val dir = File.createTempFile("stardict", "").apply { delete(); mkdirs() }
        try {
            val archive = File(dir, "a.tar").apply { writeBytes(ByteArray(2048) { 65 }) }
            val failed = runCatching { StarDictArchive.unpack(archive, File(dir, "out")) }.isFailure
            assertTrue(failed)
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val FIXTURE_TAR_BZ2 =
            "QlpoOTFBWSZTWbeqL94ATnP///+m96zNo/+U/jjvb2/3/74IhUp8JCOOA18m6TsoYmooYAgeM+0BUFVCoKSilIlUhBgTTCaY" +
            "mJoyYAmAJoMBNMABMmAABGCMTEYBNDEMmjDCTR6mJhqYCZMm0Qaeo0MQxMTRo0aeoaPU0eoaaGaAjCMJhNqGCGmTIQYE0wmm" +
            "JiaMmAJgCaDATTAATJgAARgjExGATQxDJoxBgTTCaYmJoyYAmAJoMBNMABMmAABGCMTEYBNDEMmjBNUqTFPTRTaFDZQxGnqa" +
            "DelAMhoaMgaPUxDZRoPUaMgemoAzSeoA09TbRJ6NQKUkkp6o009RpoYmajQZGamhjQIaYTEyZAyfqEZMhjQAjJobQmaDRPU2" +
            "mU+28l0Ptc3ueB4Oe6dPXqW2FvFJeRitE+DzuHdpwNRsyXKlJbuK0IS35dMSWnC8tKLRkuZQl3SEuwJLTivBVAgAgGAgAQAx" +
            "OgvtqlN0Ch3chBX4UkkOptEItsc1WsFp024RE9Et0fLjr9BXT5Sb8NWHZPk1+wIxcOCJN2vOxJ4hFJ/nFCrln6NzesTJGpa2" +
            "HYRiltPbicXoeJ+Xo/ndjxPQ/cIuLsopoi2pScBVL5BSXj4rsKRd2pFuYXdgloxOOqk56lJtieoVJw4SbYn6CSayO15AlMqi" +
            "bZT9N8SUm2J14SaynDQp9ihTbE5hSnZqKbYW/d1Kk2wvRkqdJCTbE+qqk6YUm2R6ZUneik2wvZqKaxPlSk1kdYEmsp/CvDsc" +
            "JrhPCvs7fb3dsRT3IVOl3ypMlSduJLfwukqi7UUXCKLTku2RGsTvQk1lOUqk1icUJNZT2HnOlckuHPbZU9oumyvA9T+e41TG" +
            "xz9xXaki20JcvS6tCW/1XGbGt76klysV6G7szvns+b5/qNW/vbuxsFFsKFr/fzyhb1xs2bbNRIuPuNvo6pyNrurQ2tFePKi8" +
            "IIu30sKiwIvN61ULIReUqS5eS3ySayOOFTchTbC9PjSVMFF+VSLThc2SL7/cFFkoXmEU3sLnqpTjfAVJgKecEm9kuqoloxX4" +
            "+DAiyEXsfnoiwUnmkk5zKaQprE4ISaxOpqpPc4WBUyUp0/JJUxJTxAk38p+H/cimcyCm7E+fRJrKegEmsp7RFPoc2CmFKb/9" +
            "FUmvJeGhL7lCXkUJacl6yoWvloU3YXkBJrKeyVJrE5yK3rJLrX0H8XE022uHs0Jdfd+L3uqCXM6nE6jOO3JItMEt7wqUXKyX" +
            "+CouS38pRacp72uDxnBCLrJJa8LvSotGS5D7Hp4RLR6G4KLgevQl0RJbMV2df3ootOS66UXn/3SFikW86D0aJN/KclRTvOaS" +
            "pvX8bLZRTlHQn5uLFQtrQlF7FIXRglsyn++X5biblULcRF26Eu/SS24r0u4kplRT4Ak3ZT0qFOIiTbKcoFNYnAoprE96pJrC" +
            "+7/7JSWKouH2ZQsoi7HUKFiUXjFScuJTkQk53E/GpJrI4/rZSkyhT4wk3ZTgFJrI9L+xUmEU5qKnDBTewvhqKawvAJF3/MxS" +
            "LEJcRUXz6oXq/kwlTBKd3ylJOdynSEU1ibPjhJtidQCn/ZFNsL68pTx/aKpZKi8nriiwKWvC7CEtGS7kJNZT1olNZTzqUmsL" +
            "zBKaynZJSawv38dRTKUnuhJuwufKi+Z6tIWSRf0BLVku+VJaMLtc3FQsoi9fnYRLAS+klFz5QvD/4lFlULykJbcl1pUnMBT5" +
            "IKbsTp+XQpipTXIKUxVJxopN7E80qTlglsRF0KEtWU9OSLRhekolowv1CU1ickinXSKbYnACn7MlxwS7PxMlCwkX7ZEuNkvu" +
            "1UnNgp9FCm7E4qkmsj4ipP1xSbZHqFSdr7GRKYSp3X+slRYRLkdskWIS6NSL8NSLbhc7iUiyhL/yqLkwi+ECWvKesULzhReY" +
            "KLThfTP/i7kinChIW9UX7wA="
    }
}
