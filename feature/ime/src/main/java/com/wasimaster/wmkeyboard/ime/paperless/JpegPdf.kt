package com.wasimaster.wmkeyboard.ime.paperless

import java.io.File
import java.io.FileOutputStream

/**
 * Scanned pages as one PDF, each page one of the scan's JPEGs as it is (#1198).
 *
 * Written by hand rather than through Android's PdfDocument, which would
 * decode each page and store it again uncompressed: here the JPEG's own
 * bytes go in under DCTDecode, so the PDF is the size of the pages. Paperless
 * reads the text off them itself.
 */
internal object JpegPdf {

    /** The scan resolution the page size is worked out from: the built-in scanner's, about A4 at its photo size. */
    private const val DPI = 220f

    private class Jpeg(val bytes: ByteArray, val width: Int, val height: Int, val components: Int)

    /** Writes [pages] to [out]; false when a page is not a JPEG it can read. */
    fun write(pages: List<File>, out: File): Boolean {
        val jpegs = pages.map { f -> read(f.readBytes()) ?: return false }
        if (jpegs.isEmpty()) return false
        val offsets = ArrayList<Long>()
        val counter = Counter(FileOutputStream(out).buffered())
        counter.use { w ->
            fun obj(body: () -> Unit) {
                offsets += w.count
                w.ascii("${offsets.size} 0 obj\n")
                body()
                w.ascii("\nendobj\n")
            }
            w.ascii("%PDF-1.4\n%âãÏÓ\n")
            // 1: catalog, 2: page tree; then three objects per page: page, contents, image.
            val n = jpegs.size
            val kids = (0 until n).joinToString(" ") { "${3 + it * 3} 0 R" }
            obj { w.ascii("<< /Type /Catalog /Pages 2 0 R >>") }
            obj { w.ascii("<< /Type /Pages /Kids [$kids] /Count $n >>") }
            jpegs.forEachIndexed { i, j ->
                val page = 3 + i * 3
                val pw = j.width * 72f / DPI
                val ph = j.height * 72f / DPI
                val content = "q ${fmt(pw)} 0 0 ${fmt(ph)} 0 0 cm /Im0 Do Q"
                obj {
                    w.ascii(
                        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(pw)} ${fmt(ph)}] " +
                            "/Resources << /XObject << /Im0 ${page + 2} 0 R >> >> /Contents ${page + 1} 0 R >>",
                    )
                }
                obj { w.ascii("<< /Length ${content.length} >>\nstream\n$content\nendstream") }
                obj {
                    val space = when (j.components) {
                        1 -> "/DeviceGray"
                        4 -> "/DeviceCMYK"
                        else -> "/DeviceRGB"
                    }
                    w.ascii(
                        "<< /Type /XObject /Subtype /Image /Width ${j.width} /Height ${j.height} " +
                            "/ColorSpace $space /BitsPerComponent 8 /Filter /DCTDecode /Length ${j.bytes.size} >>\nstream\n",
                    )
                    w.write(j.bytes)
                    w.ascii("\nendstream")
                }
            }
            val xref = w.count
            w.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
            offsets.forEach { w.ascii("%010d 00000 n \n".format(it)) }
            w.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        }
        return true
    }

    private fun fmt(v: Float): String = "%.2f".format(java.util.Locale.US, v)

    /** The size and channels from a JPEG's frame header, or null for anything else. */
    private fun read(b: ByteArray): Jpeg? {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 9 < b.size) {
            if (b[i] != 0xFF.toByte()) {
                i++
                continue
            }
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xFF) {
                i++
                continue
            }
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            // SOF0..SOF15, less DHT (C4), JPG (C8) and DAC (CC).
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                val c = b[i + 9].toInt() and 0xFF
                return if (w > 0 && h > 0) Jpeg(b, w, h, c) else null
            }
            i += 2 + len
        }
        return null
    }

    /** An output that knows how many bytes it has written: the cross-reference table needs every object's offset. */
    private class Counter(private val out: java.io.OutputStream) : java.io.Closeable {
        var count = 0L
            private set

        fun ascii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))

        fun write(bytes: ByteArray) {
            out.write(bytes)
            count += bytes.size
        }

        override fun close() = out.close()
    }
}
