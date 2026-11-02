package com.vehiclegate.anpr.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Drawn over the preview video: the plate search area (dim outside, dashed amber border)
 * and boxes around plates found by "Read plate now". All coordinates are 0..1 of the video frame.
 */
class RoiOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Box(val rect: RectF, val label: String)

    private var roi = RectF(0f, 0f, 1f, 1f)
    private var showRoi = true
    private var boxes: List<Box> = emptyList()
    private val density = resources.displayMetrics.density

    private val dim = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val roiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.rgb(255, 193, 7)
        pathEffect = DashPathEffect(floatArrayOf(14f * density, 8f * density), 0f)
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.rgb(0, 230, 118)
    }
    private val labelBg = Paint().apply { color = Color.rgb(0, 230, 118) }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 15f * density
        isFakeBoldText = true
    }
    private val roiText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7)
        textSize = 12f * density
    }

    fun setRoi(leftPct: Int, topPct: Int, rightPct: Int, bottomPct: Int) {
        roi = RectF(leftPct / 100f, topPct / 100f, rightPct / 100f, bottomPct / 100f)
        invalidate()
    }

    fun setShowRoi(show: Boolean) { showRoi = show; invalidate() }

    fun setBoxes(list: List<Box>) { boxes = list; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val full = roi.left <= 0f && roi.top <= 0f && roi.right >= 1f && roi.bottom >= 1f
        if (showRoi && !full) {
            val r = RectF(roi.left * w, roi.top * h, roi.right * w, roi.bottom * h)
            canvas.drawRect(0f, 0f, w, r.top, dim)
            canvas.drawRect(0f, r.bottom, w, h, dim)
            canvas.drawRect(0f, r.top, r.left, r.bottom, dim)
            canvas.drawRect(r.right, r.top, w, r.bottom, dim)
            canvas.drawRect(r, roiPaint)
            canvas.drawText("Plate search area", r.left + 6 * density, r.top + 16 * density, roiText)
        }
        for (b in boxes) {
            val r = RectF(b.rect.left * w, b.rect.top * h, b.rect.right * w, b.rect.bottom * h)
            canvas.drawRect(r, boxPaint)
            val tw = labelText.measureText(b.label) + 12 * density
            val th = 22 * density
            val top = (r.top - th).coerceAtLeast(0f)
            canvas.drawRect(r.left, top, r.left + tw, top + th, labelBg)
            canvas.drawText(b.label, r.left + 6 * density, top + th - 6 * density, labelText)
        }
    }
}
