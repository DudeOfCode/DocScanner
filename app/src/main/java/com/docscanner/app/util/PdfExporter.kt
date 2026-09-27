package com.docscanner.app.util

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/**
 * Exports a list of page image files into a single A4 PDF using the
 * platform PdfDocument. Returns the created file.
 */
object PdfExporter {

    // A4 at 72dpi
    private const val PAGE_W = 595
    private const val PAGE_H = 842

    fun export(context: Context, title: String, pagePaths: List<String>): File {
        val doc = PdfDocument()
        pagePaths.forEachIndexed { index, path ->
            val bmp = BitmapFactory.decodeFile(path) ?: return@forEachIndexed
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, index + 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            val ratio = bmp.width.toFloat() / bmp.height.toFloat()
            var rW = PAGE_W.toFloat()
            var rH = PAGE_W / ratio
            if (rH > PAGE_H) { rH = PAGE_H.toFloat(); rW = PAGE_H * ratio }
            val left = ((PAGE_W - rW) / 2f).toInt()
            val top = ((PAGE_H - rH) / 2f).toInt()
            val dst = Rect(left, top, (left + rW).toInt(), (top + rH).toInt())
            canvas.drawBitmap(bmp, null, dst, null)
            doc.finishPage(page)
            bmp.recycle()
        }
        val exportsDir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val safe = title.replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifBlank { "Document" }
        val out = File(exportsDir, "$safe.pdf")
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return out
    }
}
