package com.vehiclegate.anpr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import com.vehiclegate.anpr.camera.CameraStream
import com.vehiclegate.anpr.core.FusedVehicle
import com.vehiclegate.anpr.core.FusionParams
import com.vehiclegate.anpr.core.PlateFusionEngine
import com.vehiclegate.anpr.core.PlateRead
import com.vehiclegate.anpr.data.AppSettings
import com.vehiclegate.anpr.data.CameraConfig
import com.vehiclegate.anpr.data.VehicleEntry
import com.vehiclegate.anpr.ocr.PlateRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Wires everything together:
 *   N × CameraStream ──(plate candidates)──► PlateFusionEngine ──(one decision per vehicle)──► Room DB
 */
class AnprPipeline(
    private val app: AnprApp,
    private val scope: CoroutineScope,
    private val events: Events
) {
    interface Events {
        fun onCameraStatus(cameraId: String, status: CameraStream.Status, message: String?)
        /** Latest raw read per camera, for the live tile overlay. Main thread. */
        fun onRawRead(cameraId: String, plate: String, scorePct: Int)
        /** A new vehicle entry was stored. Main thread. */
        fun onVehicleSaved(entry: VehicleEntry)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: PlateRecognizer? = null
    private val streams = ArrayList<CameraStream>()
    private var engine: PlateFusionEngine<Bitmap>? = null
    private var running = false

    fun start(cameras: List<Pair<CameraConfig, TextureView>>, settings: AppSettings) {
        stop()
        running = true
        val rec = PlateRecognizer().also { recognizer = it }

        engine = PlateFusionEngine(
            FusionParams(
                settleMs = settings.settleMs.toLong(),
                maxTrackMs = maxOf(15_000L, settings.settleMs * 4L),
                minReads = settings.minReads.coerceAtLeast(1),
                singleReadMinScore = 0.85f,
                minConfidence = settings.minConfidencePct / 100f,
                cooldownMs = settings.cooldownSec * 1000L
            ),
            onVehicle = { v -> persist(v, settings) }
        )

        val listener = object : CameraStream.Listener {
            override fun onStatus(camera: CameraConfig, status: CameraStream.Status, message: String?) {
                events.onCameraStatus(camera.id, status, message)
            }

            override fun onPlates(
                camera: CameraConfig,
                candidates: List<PlateRecognizer.Candidate>,
                frame: Bitmap,
                timestampMs: Long
            ) {
                val eng = engine
                if (!running || eng == null) { frame.recycle(); return }
                val snapshot = makeSnapshot(frame, candidates)
                frame.recycle()
                for (c in candidates) {
                    eng.submit(PlateRead(camera.id, camera.name, c.plate, c.confidence, timestampMs, snapshot))
                }
                val top = candidates.first()
                main.post { events.onRawRead(camera.id, top.plate.formatted, (top.score * 100).toInt()) }
            }
        }

        for ((cfg, view) in cameras) {
            val s = CameraStream(app, cfg, view, rec, settings.ocrIntervalMs.toLong(), listener)
            streams += s
            s.start()
        }
        main.post(ticker)
        scope.launch(Dispatchers.IO) { cleanup(settings.retentionDays) }
    }

    fun stop() {
        running = false
        main.removeCallbacks(ticker)
        streams.forEach { it.stop() }
        streams.clear()
        // Decide on any vehicle still being tracked rather than losing it.
        engine?.flush(System.currentTimeMillis())
        engine = null
        recognizer?.close()
        recognizer = null
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            engine?.tick(System.currentTimeMillis())
            main.postDelayed(this, 500)
        }
    }

    /** Snapshot for the TV: ROI frame, ≤ 960 px wide, with the detected plate(s) boxed. */
    private fun makeSnapshot(frame: Bitmap, candidates: List<PlateRecognizer.Candidate>): Bitmap {
        val scale = min(1f, 960f / frame.width)
        val w = (frame.width * scale).toInt().coerceAtLeast(1)
        val h = (frame.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(frame, w, h, true)
        // createScaledBitmap may hand back the source itself – the caller recycles that, so copy.
        val out = if (scaled === frame || !scaled.isMutable) scaled.copy(Bitmap.Config.ARGB_8888, true) else scaled
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.rgb(0, 230, 118)
        }
        for (c in candidates.take(3)) {
            val r = c.box
            canvas.drawRect(RectF(r.left * scale - 6, r.top * scale - 6, r.right * scale + 6, r.bottom * scale + 6), paint)
        }
        return out
    }

    private fun persist(v: FusedVehicle<Bitmap>, settings: AppSettings) {
        scope.launch {
            val entry = withContext(Dispatchers.IO) {
                val path = v.snapshot?.let { saveJpeg(it, v.firstSeenMs, v.compact) }
                val summary = v.cameraVotes.joinToString("\n") {
                    listOf(it.cameraName.replace("|", "/"), it.bestText, (it.bestScore * 100).toInt(),
                        it.reads, if (it.agreesWithFinal) 1 else 0).joinToString("|")
                }
                val e = VehicleEntry(
                    plate = v.compact,
                    plateFormatted = v.formatted,
                    entryTime = v.firstSeenMs,
                    lastSeenTime = v.lastSeenMs,
                    confidence = v.confidence,
                    totalReads = v.totalReads,
                    cameraCount = v.cameraVotes.size,
                    cameraSummary = summary,
                    snapshotPath = path
                )
                e.copy(id = app.db.entries().insert(e))
            }
            events.onVehicleSaved(entry)
        }
    }

    private fun snapshotDir() = File(app.filesDir, "snapshots").apply { mkdirs() }

    private fun saveJpeg(bmp: Bitmap, ts: Long, plate: String): String? = try {
        val f = File(snapshotDir(), "${ts}_$plate.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        f.absolutePath
    } catch (e: Exception) {
        null
    }

    private suspend fun cleanup(retentionDays: Int) {
        if (retentionDays <= 0) return
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
        val dao = app.db.entries()
        dao.snapshotsBefore(cutoff).filterNotNull().forEach { File(it).delete() }
        dao.deleteBefore(cutoff)
    }
}
