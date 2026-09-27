package com.docscanner.app.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Draws the captured image fit-center with a draggable quadrilateral (4 corners
 * + 4 edge midpoints), a dimmed outside area and a rule-of-thirds grid.
 * Ported from the SVG crop overlay in the original web app.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val imgRect = RectF()

    // normalized corners: 0=tl,1=tr,2=br,3=bl
    private val corners = mutableListOf(
        PointF(0.05f, 0.05f), PointF(0.95f, 0.05f),
        PointF(0.95f, 0.95f), PointF(0.05f, 0.95f)
    )

    private var dragCorner = -1
    private var dragEdge = -1
    private var lastX = 0f
    private var lastY = 0f

    private val touchRadius = 60f
    private val handleRadius = 22f

    private val dimPaint = Paint().apply { color = Color.parseColor("#99000000") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.parseColor("#10B981")
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.parseColor("#8010B981")
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.parseColor("#10B981")
    }

    fun setBitmap(bmp: Bitmap) { bitmap = bmp; requestLayout(); invalidate() }

    fun resetCorners() {
        corners[0].set(0.05f, 0.05f); corners[1].set(0.95f, 0.05f)
        corners[2].set(0.95f, 0.95f); corners[3].set(0.05f, 0.95f)
        invalidate()
    }

    /** normalized (0..1) corners tl,tr,br,bl */
    fun getCornersNormalized(): List<PointF> = corners.map { PointF(it.x, it.y) }

    private fun computeImageRect() {
        val bmp = bitmap ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw == 0f || vh == 0f) return
        val scale = minOf(vw / bmp.width, vh / bmp.height)
        val dw = bmp.width * scale; val dh = bmp.height * scale
        val left = (vw - dw) / 2f; val top = (vh - dh) / 2f
        imgRect.set(left, top, left + dw, top + dh)
    }

    private fun toScreen(c: PointF) = PointF(
        imgRect.left + c.x * imgRect.width(),
        imgRect.top + c.y * imgRect.height()
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        computeImageRect()
        canvas.drawBitmap(bmp, null, imgRect, null)

        val sc = corners.map { toScreen(it) }

        // dim outside the quad (whole view minus quad via even-odd path)
        val path = Path().apply {
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            moveTo(sc[0].x, sc[0].y)
            lineTo(sc[1].x, sc[1].y); lineTo(sc[2].x, sc[2].y); lineTo(sc[3].x, sc[3].y)
            close()
            fillType = Path.FillType.EVEN_ODD
        }
        canvas.drawPath(path, dimPaint)

        // border
        val quad = Path().apply {
            moveTo(sc[0].x, sc[0].y)
            lineTo(sc[1].x, sc[1].y); lineTo(sc[2].x, sc[2].y); lineTo(sc[3].x, sc[3].y); close()
        }
        canvas.drawPath(quad, borderPaint)

        // rule-of-thirds grid inside quad
        fun lerp(a: PointF, b: PointF, t: Float) =
            PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        for (t in floatArrayOf(1f / 3f, 2f / 3f)) {
            val h1 = lerp(sc[0], sc[3], t); val h2 = lerp(sc[1], sc[2], t)
            canvas.drawLine(h1.x, h1.y, h2.x, h2.y, gridPaint)
            val v1 = lerp(sc[0], sc[1], t); val v2 = lerp(sc[3], sc[2], t)
            canvas.drawLine(v1.x, v1.y, v2.x, v2.y, gridPaint)
        }

        // corner handles
        sc.forEach {
            canvas.drawCircle(it.x, it.y, handleRadius, handleFill)
            canvas.drawCircle(it.x, it.y, handleRadius, handleStroke)
        }
        // edge midpoints
        edgeMidsScreen(sc).forEach {
            canvas.drawCircle(it.x, it.y, handleRadius * 0.7f, handleStroke)
        }
    }

    private fun edgeMidsScreen(sc: List<PointF>): List<PointF> = listOf(
        PointF((sc[0].x + sc[1].x) / 2, (sc[0].y + sc[1].y) / 2), // top
        PointF((sc[1].x + sc[2].x) / 2, (sc[1].y + sc[2].y) / 2), // right
        PointF((sc[2].x + sc[3].x) / 2, (sc[2].y + sc[3].y) / 2), // bottom
        PointF((sc[3].x + sc[0].x) / 2, (sc[3].y + sc[0].y) / 2)  // left
    )

    // which corners each edge moves, and along which axis
    private val edgeCorners = arrayOf(intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 0))
    private val edgeAxisX = booleanArrayOf(false, true, false, true)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val sc = corners.map { toScreen(it) }
                dragCorner = sc.indexOfFirst { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) < touchRadius }
                if (dragCorner == -1) {
                    val mids = edgeMidsScreen(sc)
                    dragEdge = mids.indexOfFirst { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) < touchRadius }
                }
                lastX = x; lastY = y
                return dragCorner != -1 || dragEdge != -1
            }
            MotionEvent.ACTION_MOVE -> {
                if (imgRect.width() == 0f) return true
                if (dragCorner != -1) {
                    val nx = ((x - imgRect.left) / imgRect.width()).coerceIn(0f, 1f)
                    val ny = ((y - imgRect.top) / imgRect.height()).coerceIn(0f, 1f)
                    corners[dragCorner].set(nx, ny)
                    invalidate()
                } else if (dragEdge != -1) {
                    val dx = (x - lastX) / imgRect.width()
                    val dy = (y - lastY) / imgRect.height()
                    for (ci in edgeCorners[dragEdge]) {
                        if (edgeAxisX[dragEdge]) corners[ci].x = (corners[ci].x + dx).coerceIn(0f, 1f)
                        else corners[ci].y = (corners[ci].y + dy).coerceIn(0f, 1f)
                    }
                    lastX = x; lastY = y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragCorner = -1; dragEdge = -1
            }
        }
        return super.onTouchEvent(event)
    }
}
