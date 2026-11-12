package com.vehiclegate.anpr.ocr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device AI models trained specifically for number plates (MIT, by ankandrew – see
 * assets/models/NOTICE.txt), run with ONNX Runtime:
 *  - [detect]: YOLOv9-t plate detector (640×640) → plate boxes
 *  - [read]:   fast-plate-ocr CCT-S global plate reader (64×128) → text + per-character confidence
 * Thread-safe; heavy, so use from a background thread.
 */
class PlateAi private constructor(
    private val env: OrtEnvironment,
    private val detector: OrtSession,
    private val reader: OrtSession
) {
    data class Detection(val box: Rect, val score: Float)

    /** Plate boxes in [bmp] coordinates, best first. */
    fun detect(bmp: Bitmap, minScore: Float = 0.2f): List<Detection> {
        val r = min(DET / bmp.width.toFloat(), DET / bmp.height.toFloat())
        val nw = max(1, (bmp.width * r).roundToInt())
        val nh = max(1, (bmp.height * r).roundToInt())
        val dw = (DET - nw) / 2f
        val dh = (DET - nh) / 2f
        val canvasBmp = Bitmap.createBitmap(DET, DET, Bitmap.Config.ARGB_8888)
        Canvas(canvasBmp).apply {
            drawColor(Color.rgb(114, 114, 114)) // letterbox padding like the original pre-processing
            val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
            drawBitmap(scaled, (dw - 0.1f).roundToInt().toFloat(), (dh - 0.1f).roundToInt().toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
            if (scaled !== bmp) scaled.recycle()
        }
        val px = IntArray(DET * DET)
        canvasBmp.getPixels(px, 0, DET, 0, 0, DET, DET)
        canvasBmp.recycle()
        val plane = DET * DET
        val fb = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val p = px[i]
            fb.put(i, ((p shr 16) and 0xFF) / 255f)
            fb.put(plane + i, ((p shr 8) and 0xFF) / 255f)
            fb.put(2 * plane + i, (p and 0xFF) / 255f)
        }
        val out = ArrayList<Detection>()
        OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, DET.toLong(), DET.toLong())).use { input ->
            detector.run(mapOf(detector.inputNames.first() to input)).use { res ->
                @Suppress("UNCHECKED_CAST")
                val rows = res[0].value as Array<FloatArray>
                for (row in rows) {
                    if (row.size < 7 || row[6] < minScore) continue
                    val x1 = ((row[1] - dw) / r).toInt().coerceIn(0, bmp.width - 1)
                    val y1 = ((row[2] - dh) / r).toInt().coerceIn(0, bmp.height - 1)
                    val x2 = ((row[3] - dw) / r).toInt().coerceIn(x1 + 1, bmp.width)
                    val y2 = ((row[4] - dh) / r).toInt().coerceIn(y1 + 1, bmp.height)
                    out += Detection(Rect(x1, y1, x2, y2), row[6])
                }
            }
        }
        return out.sortedByDescending { it.score }
    }

    /** Reads one cropped plate. Returns text (padding removed) and the mean character confidence. */
    fun read(plate: Bitmap): Pair<String, Float>? {
        val scaled = Bitmap.createScaledBitmap(plate, OCR_W, OCR_H, true)
        val px = IntArray(OCR_W * OCR_H)
        scaled.getPixels(px, 0, OCR_W, 0, 0, OCR_W, OCR_H)
        if (scaled !== plate) scaled.recycle()
        val bb = ByteBuffer.allocateDirect(OCR_W * OCR_H * 3).order(ByteOrder.nativeOrder())
        for (p in px) {
            bb.put(((p shr 16) and 0xFF).toByte()); bb.put(((p shr 8) and 0xFF).toByte()); bb.put((p and 0xFF).toByte())
        }
        bb.rewind()
        OnnxTensor.createTensor(env, bb, longArrayOf(1, OCR_H.toLong(), OCR_W.toLong(), 3), OnnxJavaType.UINT8).use { input ->
            reader.run(mapOf(reader.inputNames.first() to input)).use { res ->
                val plateOut = res.get("plate").orElse(res[0])
                @Suppress("UNCHECKED_CAST")
                val slots = (plateOut.value as Array<Array<FloatArray>>)[0]
                val sb = StringBuilder()
                var confSum = 0f
                var n = 0
                for (slot in slots) {
                    var best = 0
                    for (k in slot.indices) if (slot[k] > slot[best]) best = k
                    val ch = ALPHABET.getOrNull(best) ?: continue
                    if (ch == PAD) continue
                    sb.append(ch); confSum += slot[best]; n++
                }
                if (n == 0) return null
                return sb.toString() to confSum / n
            }
        }
    }

    companion object {
        private const val TAG = "PlateAi"
        private const val DET = 640
        private const val OCR_W = 128
        private const val OCR_H = 64
        private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_"
        private const val PAD = '_'

        @Volatile private var instance: PlateAi? = null
        @Volatile private var failed = false

        /** Loads the models once (≈13 MB). Returns null if they cannot be loaded on this device. */
        fun get(context: Context): PlateAi? {
            instance?.let { return it }
            if (failed) return null
            return synchronized(this) {
                instance ?: try {
                    val env = OrtEnvironment.getEnvironment()
                    val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
                    fun load(name: String) = context.assets.open("models/$name").use { it.readBytes() }
                    PlateAi(env, env.createSession(load("plate_detector.onnx"), opts), env.createSession(load("plate_ocr.onnx"), opts))
                        .also { instance = it }
                } catch (e: Throwable) {
                    Log.e(TAG, "AI plate models unavailable", e)
                    failed = true
                    null
                }
            }
        }
    }
}
