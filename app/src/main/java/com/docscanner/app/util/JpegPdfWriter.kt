package com.docscanner.app.util

import java.io.BufferedOutputStream
import java.io.OutputStream

/**
 * Minimal streaming PDF writer that embeds JPEG data as-is (DCTDecode).
 * This is what makes scanner-style PDFs small: the JPEG bytes are stored
 * directly instead of being re-encoded losslessly like android.graphics.pdf.PdfDocument does.
 * Pure JVM, no Android dependencies, one page held in memory at a time.
 */
class JpegPdfWriter(out: OutputStream, private val title: String = "") {

    private val os = CountingStream(BufferedOutputStream(out, 64 * 1024))
    private val offsets = mutableListOf<Long>()   // offsets[i] = byte offset of object i+1
    private val pageObjIds = mutableListOf<Int>()
    private var nextObj = 3                        // 1 = catalog, 2 = pages tree
    private var finished = false

    init {
        os.writeAscii("%PDF-1.4\n")
        os.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        offsets.add(-1); offsets.add(-1) // placeholders for objects 1 and 2
    }

    /**
     * @param jpeg   baseline JPEG bytes
     * @param imgW   JPEG pixel width
     * @param imgH   JPEG pixel height
     * @param pageW  page width in points
     * @param pageH  page height in points
     * @param x,y,w,h  where the image is drawn, in points, origin top-left
     */
    fun addJpegPage(
        jpeg: ByteArray, imgW: Int, imgH: Int,
        pageW: Float, pageH: Float,
        x: Float, y: Float, w: Float, h: Float
    ) {
        check(!finished)
        val imgId = nextObj++
        val contentId = nextObj++
        val pageId = nextObj++

        // Image XObject
        startObj(imgId)
        os.writeAscii("<< /Type /XObject /Subtype /Image /Width $imgW /Height $imgH " +
            "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n")
        os.write(jpeg)
        os.writeAscii("\nendstream\nendobj\n")

        // Content stream: draw image into rect (PDF origin is bottom-left)
        val pdfY = pageH - y - h
        val content = "q\n${f(w)} 0 0 ${f(h)} ${f(x)} ${f(pdfY)} cm\n/Im0 Do\nQ\n"
        startObj(contentId)
        os.writeAscii("<< /Length ${content.length} >>\nstream\n$content" + "endstream\nendobj\n")

        // Page
        startObj(pageId)
        os.writeAscii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(pageW)} ${f(pageH)}] " +
            "/Resources << /XObject << /Im0 $imgId 0 R >> >> /Contents $contentId 0 R >>\nendobj\n")
        pageObjIds.add(pageId)
    }

    fun finish() {
        if (finished) return
        finished = true
        // Pages tree
        startObj(2)
        val kids = pageObjIds.joinToString(" ") { "$it 0 R" }
        os.writeAscii("<< /Type /Pages /Kids [$kids] /Count ${pageObjIds.size} >>\nendobj\n")
        // Catalog
        startObj(1)
        os.writeAscii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        // Info
        val infoId = nextObj++
        startObj(infoId)
        os.writeAscii("<< /Title (${escape(title)}) /Producer (DocScanner) >>\nendobj\n")

        val xrefPos = os.count
        val total = nextObj
        os.writeAscii("xref\n0 $total\n0000000000 65535 f \n")
        for (i in 1 until total) {
            os.writeAscii(String.format("%010d 00000 n \n", offsets[i - 1]))
        }
        os.writeAscii("trailer\n<< /Size $total /Root 1 0 R /Info $infoId 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        os.flush()
    }

    private fun startObj(id: Int) {
        while (offsets.size < id) offsets.add(-1)
        offsets[id - 1] = os.count
        os.writeAscii("$id 0 obj\n")
    }

    private fun f(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)

    private fun escape(s: String): String = buildString {
        for (c in s) when {
            c == '(' || c == ')' || c == '\\' -> append('\\').append(c)
            c.code in 32..126 -> append(c)
            else -> append('_')
        }
    }

    private class CountingStream(private val inner: OutputStream) : OutputStream() {
        var count = 0L; private set
        override fun write(b: Int) { inner.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); count += len }
        override fun flush() = inner.flush()
        fun writeAscii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))
    }
}
