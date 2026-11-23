package com.scandoc.app

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale

/** A scanned page that is already a JPEG file. */
class JpegPage(val file: File, val width: Int, val height: Int)

/**
 * Tiny PDF writer that embeds the JPEG bytes as-is (DCTDecode). Files stay small and sharp, unlike
 * android.graphics.pdf.PdfDocument, which re-compresses every bitmap losslessly. Page width is A4 (595.28 pt),
 * page height follows the picture's aspect ratio.
 *
 * No Android classes here on purpose, so it can be unit-tested on a plain JVM.
 */
object PdfWriter {

    private const val PAGE_WIDTH = 595.28

    private class Counting(private val inner: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) { inner.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); count += len }
        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }

    fun write(out: File, pages: List<JpegPage>) {
        require(pages.isNotEmpty()) { "No pages" }

        Counting(BufferedOutputStream(FileOutputStream(out))).use { s ->
            val offsets = LongArray(3 + 3 * pages.size)   // index = object number

            fun ascii(text: String) = s.write(text.toByteArray(Charsets.ISO_8859_1))
            fun beginObj(num: Int) {
                offsets[num] = s.count
                ascii("$num 0 obj\n")
            }

            ascii("%PDF-1.4\n")
            s.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

            beginObj(1)
            ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

            val kids = pages.indices.joinToString(" ") { "${3 + 3 * it} 0 R" }
            beginObj(2)
            ascii("<< /Type /Pages /Kids [ $kids ] /Count ${pages.size} >>\nendobj\n")

            pages.forEachIndexed { i, page ->
                val pageObj = 3 + 3 * i
                val contentObj = pageObj + 1
                val imageObj = pageObj + 2

                val pw = PAGE_WIDTH
                val ph = PAGE_WIDTH * page.height / page.width
                val wStr = String.format(Locale.US, "%.2f", pw)
                val hStr = String.format(Locale.US, "%.2f", ph)

                beginObj(pageObj)
                ascii(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [ 0 0 $wStr $hStr ] " +
                        "/Resources << /XObject << /Im0 $imageObj 0 R >> >> /Contents $contentObj 0 R >>\nendobj\n"
                )

                val content = "q $wStr 0 0 $hStr 0 0 cm /Im0 Do Q\n"
                beginObj(contentObj)
                ascii("<< /Length ${content.length} >>\nstream\n")
                ascii(content)
                ascii("endstream\nendobj\n")

                beginObj(imageObj)
                ascii(
                    "<< /Type /XObject /Subtype /Image /Width ${page.width} /Height ${page.height} " +
                        "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${page.file.length()} >>\nstream\n"
                )
                FileInputStream(page.file).use { it.copyTo(s) }
                ascii("\nendstream\nendobj\n")
            }

            val xrefStart = s.count
            val size = offsets.size
            ascii("xref\n0 $size\n")
            ascii("0000000000 65535 f \n")
            for (n in 1 until size) {
                ascii(String.format(Locale.US, "%010d %05d n \n", offsets[n], 0))
            }
            ascii("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xrefStart\n%%EOF\n")
        }
    }
}
