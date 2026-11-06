package com.vehiclegate.anpr.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Drawn over the preview video:
 *  - the plate search area (dims everything outside it), editable by touch or TV remote,
 *  - yellow boxes: plate regions the locator found,
 *  - green boxes + text: plates that were read.
 * All coordinates are 0..1 of the video frame.
 */
class RoiOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Box(val rect: RectF, val label: String)

    interface Listener { fun onRoiChanged(roi: RectF) }

    var listener: Listener? = null
    private var roi = RectF(0f, 0f, 1f, 1f)
    private var showRoi = true
    private var boxes: List<Box> = emptyList()
    private var regions: List<RectF> = emptyList()

    /** Editing: touch-drag to draw/move/resize; [nudge] for D-pad. */
    var editing = false
        set(v) { field = v; invalidate() }
    /** D-pad mode while editing: false = arrows move the area, true = arrows resize it. */
    var resizeMode = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val dim = Paint().apply { color = Color.argb(120, 0, 0, 0) }
    private val roiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = Color.rgb(255, 193, 7)
        pathEffect = DashPathEffect(floatArrayOf(14f * d, 8f * d), 0f)
    }
    private val roiEditPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.5f * d; color = Color.rgb(255, 193, 7)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 193, 7) }
    private val regionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Color.rgb(255, 235, 59)
        pathEffect = DashPathEffect(floatArrayOf(6f * d, 4f * d), 0f)
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Color.rgb(0, 230, 118)
    }
    private val labelBg = Paint().apply { color = Color.rgb(0, 230, 118) }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 15f * d; isFakeBoldText = true
    }
    private val roiText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7); textSize = 13f * d; isFakeBoldText = true
        setShadowLayer(3f * d, 0f, 0f, Color.BLACK)
    }

    fun setRoi(leftPct: Int, topPct: Int, rightPct: Int, bottomPct: Int) =
        setRoi(RectF(leftPct / 100f, topPct / 100f, rightPct / 100f, bottomPct / 100f))

    fun setRoi(r: RectF) { roi = RectF(r); invalidate() }
    fun getRoi() = RectF(roi)
    fun setShowRoi(show: Boolean) { showRoi = show; invalidate() }
    fun setBoxes(list: List<Box>) { boxes = list; invalidate() }
    fun setRegions(list: List<RectF>) { regions = list; invalidate() }

    /** D-pad editing: move or resize the area by a step (fraction of the frame). */
    fun nudge(dx: Float, dy: Float) {
        val r = RectF(roi)
        if (resizeMode) {
            r.right = (r.right + dx).coerceIn(r.left + MIN_SIZE, 1f)
            r.bottom = (r.bottom + dy).coerceIn(r.top + MIN_SIZE, 1f)
        } else {
            val ox = dx.coerceIn(-r.left, 1f - r.right)
            val oy = dy.coerceIn(-r.top, 1f - r.bottom)
            r.offset(ox, oy)
        }
        roi = r
        invalidate()
        listener?.onRoiChanged(RectF(roi))
    }

    // ------------------------------------------------------------------ touch editing

    private enum class Drag { NONE, NEW, MOVE, TL, TR, BL, BR }
    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var startRoi = RectF()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editing || width == 0 || height == 0) return false
        val x = (e.x / width).coerceIn(0f, 1f)
        val y = (e.y / height).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; startRoi = RectF(roi)
                val tx = 28 * d / width
                val ty = 28 * d / height
                fun near(px: Float, py: Float) = abs(x - px) < tx && abs(y - py) < ty
                drag = when {
                    near(roi.left, roi.top) -> Drag.TL
                    near(roi.right, roi.top) -> Drag.TR
                    near(roi.left, roi.bottom) -> Drag.BL
                    near(roi.right, roi.bottom) -> Drag.BR
                    roi.contains(x, y) && !(roi.left <= 0f && roi.top <= 0f && roi.right >= 1f && roi.bottom >= 1f) -> Drag.MOVE
                    else -> Drag.NEW
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val r = RectF(startRoi)
                when (drag) {
                    Drag.NEW -> r.set(min(downX, x), min(downY, y), max(downX, x), max(downY, y))
                    Drag.MOVE -> r.offset((x - downX).coerceIn(-r.left, 1f - r.right), (y - downY).coerceIn(-r.top, 1f - r.bottom))
                    Drag.TL -> { r.left = min(x, r.right - MIN_SIZE); r.top = min(y, r.bottom - MIN_SIZE) }
                    Drag.TR -> { r.right = max(x, r.left + MIN_SIZE); r.top = min(y, r.bottom - MIN_SIZE) }
                    Drag.BL -> { r.left = min(x, r.right - MIN_SIZE); r.bottom = max(y, r.top + MIN_SIZE) }
                    Drag.BR -> { r.right = max(x, r.left + MIN_SIZE); r.bottom = max(y, r.top + MIN_SIZE) }
                    Drag.NONE -> {}
                }
                roi = r
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag == Drag.NEW && (roi.width() < MIN_SIZE || roi.height() < MIN_SIZE)) roi = startRoi
                drag = Drag.NONE
                invalidate()
                listener?.onRoiChanged(RectF(roi))
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val full = roi.left <= 0f && roi.top <= 0f && roi.right >= 1f && roi.bottom >= 1f
        val r = RectF(roi.left * w, roi.top * h, roi.right * w, roi.bottom * h)
        if ((showRoi || editing) && !full) {
            canvas.drawRect(0f, 0f, w, r.top, dim)
            canvas.drawRect(0f, r.bottom, w, h, dim)
            canvas.drawRect(0f, r.top, r.left, r.bottom, dim)
            canvas.drawRect(r.right, r.top, w, r.bottom, dim)
        }
        if (editing) {
            canvas.drawRect(r, roiEditPaint)
            val hs = 9 * d
            for ((px, py) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom))
                canvas.drawRect(px - hs, py - hs, px + hs, py + hs, handlePaint)
            val label = if (resizeMode) "RESIZE (arrows) – OK: move" else "MOVE (arrows) – OK: resize"
            canvas.drawText("Search area  ${(roi.width() * 100).toInt()}%×${(roi.height() * 100).toInt()}%  · $label",
                r.left + 8 * d, max(r.top - 8 * d, 18 * d), roiText)
        } else if (showRoi && !full) {
            canvas.drawRect(r, roiPaint)
            canvas.drawText("Plate search area", r.left + 6 * d, r.top + 17 * d, roiText)
        }
        for (g in regions) canvas.drawRect(g.left * w, g.top * h, g.right * w, g.bottom * h, regionPaint)
        for (b in boxes) {
            val br = RectF(b.rect.left * w, b.rect.top * h, b.rect.right * w, b.rect.bottom * h)
            canvas.drawRect(br, boxPaint)
            val tw = labelText.measureText(b.label) + 12 * d
            val th = 22 * d
            val top = (br.top - th).coerceAtLeast(0f)
            canvas.drawRect(br.left, top, br.left + tw, top + th, labelBg)
            canvas.drawText(b.label, br.left + 6 * d, top + th - 6 * d, labelText)
        }
    }

    companion object { private const val MIN_SIZE = 0.05f }
}
