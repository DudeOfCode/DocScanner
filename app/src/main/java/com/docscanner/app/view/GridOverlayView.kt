package com.docscanner.app.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Simple rule-of-thirds 3x3 grid overlay for the camera preview. */
class GridOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFFFFF"); strokeWidth = 1.5f; style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        for (i in 1..2) {
            val x = w * i / 3f; val y = h * i / 3f
            canvas.drawLine(x, 0f, x, h, paint)
            canvas.drawLine(0f, y, w, y, paint)
        }
    }
}
