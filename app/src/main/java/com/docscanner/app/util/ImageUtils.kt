package com.docscanner.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Image processing helpers ported from the original web app:
 *  - perspective crop (homography warp) via Matrix.setPolyToPoly
 *  - enhancement filters (magic color / grayscale / black & white)
 *  - auto-enhance used when importing images
 */
object ImageUtils {

    /**
     * Warp the quadrilateral described by 4 normalized corners (tl,tr,br,bl)
     * into an upright rectangle, correcting perspective.
     */
    fun perspectiveCrop(src: Bitmap, cornersNorm: List<PointF>): Bitmap {
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val p = cornersNorm.map { PointF(it.x * w, it.y * h) }

        val wTop = hypot((p[0].x - p[1].x).toDouble(), (p[0].y - p[1].y).toDouble())
        val wBot = hypot((p[3].x - p[2].x).toDouble(), (p[3].y - p[2].y).toDouble())
        val hLeft = hypot((p[0].x - p[3].x).toDouble(), (p[0].y - p[3].y).toDouble())
        val hRight = hypot((p[1].x - p[2].x).toDouble(), (p[1].y - p[2].y).toDouble())
        val dstW = max(1.0, max(wTop, wBot)).toInt()
        val dstH = max(1.0, max(hLeft, hRight)).toInt()

        // source polygon points (x,y pairs)
        val srcPoly = floatArrayOf(
            p[0].x, p[0].y, p[1].x, p[1].y, p[2].x, p[2].y, p[3].x, p[3].y
        )
        val dstPoly = floatArrayOf(
            0f, 0f, dstW.toFloat(), 0f, dstW.toFloat(), dstH.toFloat(), 0f, dstH.toFloat()
        )
        // Matrix maps src -> dst; for drawing we need dst<-src so invert usage.
        val matrix = Matrix()
        matrix.setPolyToPoly(srcPoly, 0, dstPoly, 0, 4)

        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, matrix, paint)
        return out
    }

    enum class Filter { ORIGINAL, MAGIC, GRAYSCALE, BW }

    fun applyFilter(src: Bitmap, filter: Filter): Bitmap {
        if (filter == Filter.ORIGINAL) return src.copy(Bitmap.Config.ARGB_8888, false)
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            var r = (c shr 16) and 0xFF
            var g = (c shr 8) and 0xFF
            var b = c and 0xFF
            val lum = 0.299 * r + 0.587 * g + 0.114 * b
            when (filter) {
                Filter.GRAYSCALE -> { val v = lum.toInt().coerceIn(0, 255); r = v; g = v; b = v }
                Filter.BW -> { val v = if (lum > 130) 255 else 0; r = v; g = v; b = v }
                Filter.MAGIC -> {
                    val factor = 1.4
                    r = min(255.0, (r - 128) * factor + 128 + 30).toInt()
                    g = min(255.0, (g - 128) * factor + 128 + 30).toInt()
                    b = min(255.0, (b - 128) * factor + 128 + 30).toInt()
                    if (lum > 140) { r = min(255, r + 50); g = min(255, g + 50); b = min(255, b + 50) }
                    else { r = max(0, r - 20); g = max(0, g - 20); b = max(0, b - 20) }
                }
                else -> {}
            }
            pixels[i] = (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** Light auto-enhance applied to imported images (matches web autoProcessImage). */
    fun autoEnhance(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            var r = (c shr 16) and 0xFF
            var g = (c shr 8) and 0xFF
            var b = c and 0xFF
            val lum = 0.299 * r + 0.587 * g + 0.114 * b
            val factor = 1.2
            r = min(255.0, (r - 128) * factor + 128 + 20).toInt()
            g = min(255.0, (g - 128) * factor + 128 + 20).toInt()
            b = min(255.0, (b - 128) * factor + 128 + 20).toInt()
            if (lum > 150) { r = 255; g = 255; b = 255 }
            else { r = max(0, r - 10); g = max(0, g - 10); b = max(0, b - 10) }
            pixels[i] = (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    fun rotate90(src: Bitmap): Bitmap {
        val m = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }
}
