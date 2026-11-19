package com.docscanner.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Exports page images into a single PDF with scanner-style compression:
 * each page is downscaled to a sensible print resolution and stored as a JPEG
 * inside the PDF (via [JpegPdfWriter]). This gives small files that stay sharp,
 * similar to what online compressors produce.
 */
object PdfExporter {

    enum class Quality(val label: String, val dpi: Int, val jpegQuality: Int) {
        HIGH("High quality (clearest, larger file)", 200, 85),
        BALANCED("Balanced (recommended)", 150, 75),
        SMALL("Small file (smallest size)", 100, 60)
    }

    // A4 at 72 dpi (points)
    private const val A4_W = 595f
    private const val A4_H = 842f

    fun export(
        context: Context,
        title: String,
        pagePaths: List<String>,
        quality: Quality = Quality.BALANCED
    ): File {
        val exportsDir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val safe = title.replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifBlank { "Document" }
        val out = File(exportsDir, "$safe.pdf")

        FileOutputStream(out).use { fos ->
            val writer = JpegPdfWriter(fos, title)
            for (path in pagePaths) {
                // Read size first so we never decode a full-res photo when we don't need it
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                val srcW = bounds.outWidth
                val srcH = bounds.outHeight
                if (srcW <= 0 || srcH <= 0) continue

                // Landscape pages for wide images, portrait otherwise
                val landscape = srcW > srcH
                val pageW = if (landscape) A4_H else A4_W
                val pageH = if (landscape) A4_W else A4_H

                // Fit inside the page, keep aspect ratio, centered
                val scale = minOf(pageW / srcW, pageH / srcH)
                val drawW = srcW * scale
                val drawH = srcH * scale
                val x = (pageW - drawW) / 2f
                val y = (pageH - drawH) / 2f

                // Target pixel size for the chosen dpi (never upscale)
                val pxScale = minOf(1f, quality.dpi / 72f * drawW / srcW)
                val targetW = maxOf(1, (srcW * pxScale).toInt())
                val targetH = maxOf(1, (srcH * pxScale).toInt())

                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(srcW, srcH, targetW, targetH)
                }
                val decoded = BitmapFactory.decodeFile(path, opts) ?: continue
                val prepared = prepare(decoded, targetW, targetH)

                val baos = ByteArrayOutputStream(256 * 1024)
                prepared.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, baos)
                writer.addJpegPage(
                    baos.toByteArray(), prepared.width, prepared.height,
                    pageW, pageH, x, y, drawW, drawH
                )
                if (prepared !== decoded) prepared.recycle()
                decoded.recycle()
            }
            writer.finish()
        }
        return out
    }

    private fun sampleSizeFor(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
        var s = 1
        while (srcW / (s * 2) >= targetW && srcH / (s * 2) >= targetH) s *= 2
        return s
    }

    /** Scale down smoothly if still larger than target, and flatten transparency onto white. */
    private fun prepare(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        var bmp = src
        if (bmp.width > targetW || bmp.height > targetH) {
            bmp = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
        }
        if (bmp.hasAlpha()) {
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            if (bmp !== src) bmp.recycle()
            bmp = flat
        }
        return bmp
    }
}
