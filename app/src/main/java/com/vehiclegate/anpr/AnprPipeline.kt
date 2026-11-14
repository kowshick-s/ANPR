package com.vehiclegate.anpr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
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
        /** A plate was read but not logged (duplicate, too unclear …) – main thread. */
        fun onSkipped(plate: String, reason: String) {}

        /** Small live picture of a camera, about once a second (main thread). */
        fun onCameraPicture(cameraId: String, picture: Bitmap) {}

        /** Plate-reading activity per camera (main thread). */
        fun onCameraScan(cameraId: String, scans: Int, motion: Boolean) {}

        /** A vehicle was added to (or merged into) the CAPTURED waiting list. Main thread. */
        fun onCaptured(capture: com.vehiclegate.anpr.data.Capture, isNew: Boolean) {}

        /** A monthly vehicle was seen leaving and checked out automatically. Main thread. */
        fun onAutoCheckedOut(entry: VehicleEntry) {}

        /** A new vehicle was checked in. Main thread. */
        fun onVehicleSaved(entry: VehicleEntry)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: PlateRecognizer? = null
    private val streams = ArrayList<CameraStream>()
    /** Pictures kept for a read: small scene (plate boxed) + plate crop. */
    class Snap(val scene: Bitmap, val plate: Bitmap?)

    private var engine: PlateFusionEngine<Snap>? = null
    /** Stops re-reading plates that stand at the same spot (each read of them gives a new wrong number). */
    private val stationary = com.vehiclegate.anpr.core.StationaryFilter()
    private var running = false

    fun start(cameras: List<CameraConfig>, settings: AppSettings) {
        stop()
        running = true
        com.vehiclegate.anpr.core.IndianPlateParser.preferredStates =
            com.vehiclegate.anpr.core.IndianPlateParser.parseStateList(settings.commonStates)
        com.vehiclegate.anpr.core.IndianPlateParser.strict = settings.strictStates
        val rec = PlateRecognizer(app, settings.useAiPlates).also { recognizer = it }

        engine = PlateFusionEngine(
            FusionParams(
                // ≥ 4 s so two idle scans (every ~1.5–2 s) of a slow/stopped vehicle land in one track
                settleMs = maxOf(4_000L, settings.settleMs.toLong()),
                maxTrackMs = maxOf(15_000L, settings.settleMs * 4L),
                minReads = settings.minReads.coerceAtLeast(1),
                singleReadMinScore = 0.85f,
                minConfidence = settings.minConfidencePct / 100f,
                cooldownMs = settings.cooldownSec * 1000L
            ),
            onVehicle = { v -> persist(v, settings) },
            onDiscarded = { plate, reason -> main.post { events.onSkipped(plate, reason) } }
        )

        val listener = object : CameraStream.Listener {
            override fun onStatus(camera: CameraConfig, status: CameraStream.Status, message: String?) {
                events.onCameraStatus(camera.id, status, message)
            }

            override fun onScan(camera: CameraConfig, scans: Int, motion: Boolean) {
                events.onCameraScan(camera.id, scans, motion)
            }

            override fun onThumbnail(camera: CameraConfig, picture: Bitmap) {
                events.onCameraPicture(camera.id, picture)
            }

            override fun onPlates(
                camera: CameraConfig,
                candidates: List<PlateRecognizer.Candidate>,
                frame: Bitmap,
                timestampMs: Long
            ) {
                val eng = engine
                if (!running || eng == null) { frame.recycle(); return }
                val fw = frame.width.toFloat().coerceAtLeast(1f)
                val fh = frame.height.toFloat().coerceAtLeast(1f)
                val fresh = candidates.filter { c ->
                    stationary.accept(camera.id, c.box.exactCenterX() / fw, c.box.exactCenterY() / fh, timestampMs)
                }
                if (fresh.isEmpty()) { frame.recycle(); return } // plate standing still – not read again
                val snapshot = makeSnapshot(frame, fresh)
                frame.recycle()
                for (c in fresh) {
                    eng.submit(PlateRead(camera.id, camera.name, c.plate, c.confidence, timestampMs, snapshot,
                        posX = c.box.exactCenterX() / fw, posY = c.box.exactCenterY() / fh))
                }
                val top = fresh.first()
                main.post { events.onRawRead(camera.id, top.plate.formatted, (top.score * 100).toInt()) }
            }
        }

        for (cfg in cameras) {
            val s = CameraStream(app, cfg, rec, settings.ocrIntervalMs.toLong(), settings.showLiveVideo,
                settings.movingOnly, listener)
            streams += s
            s.start()
        }
        main.post(ticker)
        scope.launch(Dispatchers.IO) {
            while (running) {
                try { cleanup(settings.retentionDays, settings.keepPicturesDays) } catch (_: Exception) {}
                kotlinx.coroutines.delay(TimeUnit.HOURS.toMillis(6))
            }
        }
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

    /** Small scene picture (≤ 640 px, plate boxed) + crop of the best plate (≤ 360 px). */
    private fun makeSnapshot(frame: Bitmap, candidates: List<PlateRecognizer.Candidate>): Snap {
        val plate = try {
            val b = candidates.first().box
            val padX = (b.width() * 0.15f).toInt(); val padY = (b.height() * 0.35f).toInt()
            val r = android.graphics.Rect(b.left - padX, b.top - padY, b.right + padX, b.bottom + padY)
            if (r.intersect(0, 0, frame.width, frame.height) && r.width() > 8 && r.height() > 4) {
                val crop = Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height())
                val s = min(1f, 360f / crop.width)
                if (s < 1f) Bitmap.createScaledBitmap(crop, (crop.width * s).toInt().coerceAtLeast(1),
                    (crop.height * s).toInt().coerceAtLeast(1), true).also { if (it !== crop) crop.recycle() }
                else crop.copy(Bitmap.Config.ARGB_8888, false).also { if (it !== crop) crop.recycle() }
            } else null
        } catch (_: Exception) { null }
        return Snap(sceneOf(frame, candidates), plate)
    }

    private fun sceneOf(frame: Bitmap, candidates: List<PlateRecognizer.Candidate>): Bitmap {
        val scale = min(1f, 640f / frame.width)
        val w = (frame.width * scale).toInt().coerceAtLeast(1)
        val h = (frame.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(frame, w, h, true)
        // createScaledBitmap may hand back the source itself – the caller recycles that, so copy.
        val out = if (scaled === frame || !scaled.isMutable) scaled.copy(Bitmap.Config.ARGB_8888, true) else scaled
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.rgb(0, 230, 118)
        }
        for (c in candidates.take(3)) {
            val r = c.box
            canvas.drawRect(RectF(r.left * scale - 6, r.top * scale - 6, r.right * scale + 6, r.bottom * scale + 6), paint)
        }
        return out
    }

    private fun persist(v: FusedVehicle<Snap>, settings: AppSettings) {
        scope.launch {
            val entry = withContext(Dispatchers.IO) {
                val path = v.snapshot?.let { saveSnap(it, v.firstSeenMs, v.compact, settings.plateCropOnly) }
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
                // Parking rules decide: monthly auto check-in/out, waiting list, or ignore.
                // (The desk keeps or deletes the picture.)
                val pos = v.cameraId?.let { ParkingDesk.Pos(it, v.posX, v.posY) }?.takeIf { it.x >= 0 }
                app.parking.onDetected(e, app.settingsStore.settings(), pos)
            }
            when (entry) {
                is ParkingDesk.Result.CheckedIn -> events.onVehicleSaved(entry.entry)
                is ParkingDesk.Result.Captured -> events.onCaptured(entry.capture, entry.isNew)
                is ParkingDesk.Result.AutoCheckedOut -> events.onAutoCheckedOut(entry.entry)
                is ParkingDesk.Result.Ignored -> events.onSkipped(entry.plate, entry.reason)
            }
        }
    }

    private fun snapshotDir() = File(app.filesDir, "snapshots").apply { mkdirs() }

    /**
     * Saves the scene (quality 70) and the plate crop next to it ("…_plate.jpg", quality 85).
     * Crop-only mode: just the plate crop at the returned path. About 50 KB per vehicle (crop only ~10 KB).
     */
    private fun saveSnap(s: Snap, ts: Long, plate: String, cropOnly: Boolean): String? = try {
        val f = File(snapshotDir(), "${ts}_$plate.jpg")
        if (cropOnly && s.plate != null) {
            FileOutputStream(f).use { s.plate.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } else {
            FileOutputStream(f).use { s.scene.compress(Bitmap.CompressFormat.JPEG, 70, it) }
            s.plate?.let { p ->
                FileOutputStream(File(snapshotDir(), "${ts}_${plate}_plate.jpg")).use { p.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }
        }
        f.absolutePath
    } catch (e: Exception) {
        null
    }

    private suspend fun cleanup(retentionDays: Int, keepPicturesDays: Int) {
        // pictures: kept until check-out + N days (the visit record itself stays)
        if (keepPicturesDays > 0) {
            val before = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(keepPicturesDays.toLong())
            for (e in app.db.entries().picturesToDrop(before)) {
                Pictures.delete(e.snapshotPath)
                app.db.entries().update(e.copy(snapshotPath = null))
            }
        }
        if (retentionDays <= 0) return
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
        val dao = app.db.entries()
        dao.snapshotsBefore(cutoff).filterNotNull().forEach { Pictures.delete(it) }
        dao.deleteBefore(cutoff)
        app.db.captures().deleteBefore(cutoff)
    }
}
