package com.docscanner.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Exports pages as individual image files (JPG or PNG).
 * If a page is already stored in the requested format it is copied untouched,
 * so there is no re-compression and no quality loss.
 */
object ImageExporter {

    enum class Format(val ext: String, val mime: String, val label: String) {
        JPG("jpg", "image/jpeg", "JPG images (smaller files)"),
        PNG("png", "image/png", "PNG images (lossless, larger files)")
    }

    private const val JPEG_QUALITY = 92

    fun export(context: Context, title: String, pagePaths: List<String>, format: Format): List<File> {
        val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val safe = title.replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifBlank { "Document" }

        // Remove stale image exports of this document so old pages don't get mixed in
        dir.listFiles { f -> f.name.startsWith("${safe}_page") && (f.extension == "jpg" || f.extension == "png") }
            ?.forEach { it.delete() }

        val result = mutableListOf<File>()
        pagePaths.forEachIndexed { i, path ->
            val src = File(path)
            if (!src.exists()) return@forEachIndexed
            val name = String.format(Locale.US, "%s_page%02d.%s", safe, i + 1, format.ext)
            val dest = File(dir, name)

            val srcExt = src.extension.lowercase(Locale.US)
            val sameFormat = (format == Format.JPG && (srcExt == "jpg" || srcExt == "jpeg")) ||
                (format == Format.PNG && srcExt == "png")

            if (sameFormat) {
                src.copyTo(dest, overwrite = true)
            } else {
                val bmp = BitmapFactory.decodeFile(path) ?: return@forEachIndexed
                val toSave = if (format == Format.JPG && bmp.hasAlpha()) flattenOnWhite(bmp) else bmp
                FileOutputStream(dest).use { fos ->
                    if (format == Format.JPG) toSave.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
                    else toSave.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                if (toSave !== bmp) toSave.recycle()
                bmp.recycle()
            }
            result.add(dest)
        }
        return result
    }

    private fun flattenOnWhite(src: Bitmap): Bitmap {
        val flat = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
        return flat
    }
}
