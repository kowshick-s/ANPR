package com.vehiclegate.anpr.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.vehiclegate.anpr.data.CameraConfig
import com.vehiclegate.anpr.ocr.PlateRecognizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One RTSP camera, scanned in the background:
 *  - LibVLC decodes the stream into an [OffscreenFrameSource] (no screen/View needed, so it keeps
 *    working when the app is minimised or the screen is off),
 *  - a frame loop on the source's own thread watches for motion in the search area and sends
 *    full-HD crops of the search area to plate reading (fast during motion, slow when idle),
 *  - auto-reconnects with back-off when the stream drops or freezes.
 * [start]/[stop] must be called on the main thread.
 */
class CameraStream(
    private val context: Context,
    val config: CameraConfig,
    private val recognizer: PlateRecognizer,
    private val ocrIntervalMs: Long,
    /** Send a small picture about once a second for the dashboard tiles. */
    private val thumbnails: Boolean,
    /** Keep only plates where something moved recently (vehicle being ridden in, not parked). */
    private val movingOnly: Boolean,
    private val listener: Listener
) {
    enum class Status { CONNECTING, LIVE, RECONNECTING, STOPPED }

    interface Listener {
        /** Main thread. */
        fun onStatus(camera: CameraConfig, status: Status, message: String?)

        /**
         * Background thread. [frame] is the search-area image the plates were found in
         * (box coordinates refer to it); ownership passes to the listener.
         */
        fun onPlates(camera: CameraConfig, candidates: List<PlateRecognizer.Candidate>, frame: Bitmap, timestampMs: Long)

        /** A frame was sent for plate reading (main thread). */
        fun onScan(camera: CameraConfig, scans: Int, motion: Boolean) {}

        /** Small live picture for the dashboard (main thread). */
        fun onThumbnail(camera: CameraConfig, picture: Bitmap) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private var source: OffscreenFrameSource? = null
    private var player: VlcPlayer? = null
    @Volatile private var running = false
    @Volatile private var status = Status.STOPPED
    private var retryDelayMs = INITIAL_RETRY_MS

    // watchdog (main thread)
    private var connectedAt = 0L
    private var lastProgressAt = 0L
    private var lastFrames = 0L

    // frame loop (source thread)
    private val motion = MotionDetector()
    private var smallFrame: Bitmap? = null
    private var lastMotionAt = 0L
    private var lastOcrAt = 0L
    private var lastThumbAt = 0L
    private var ocrCycle = 0
    private var scans = 0
    @Volatile private var ocrBusy = false

    fun start() {
        if (running) return
        running = true
        val src = OffscreenFrameSource(RENDER_W, RENDER_H, config.name)
        source = src
        if (src.error != null) {
            setStatus(Status.RECONNECTING, src.error)
        }
        connect()
        main.postDelayed(watchdog, WATCHDOG_MS)
        src.handler.postDelayed(frameLoop, FRAME_LOOP_MS)
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        source?.handler?.removeCallbacksAndMessages(null)
        releasePlayer()
        source?.release()
        source = null
        setStatus(Status.STOPPED, null)
    }

    // ---------------------------------------------------------------- playback (LibVLC → off-screen surface)

    private fun connect() {
        val src = source ?: return
        releasePlayer()
        setStatus(if (retryDelayMs > INITIAL_RETRY_MS) Status.RECONNECTING else Status.CONNECTING, null)
        connectedAt = SystemClock.elapsedRealtime()
        lastProgressAt = connectedAt
        lastFrames = src.frames

        val p = VlcPlayer(context, src.surface, RENDER_W, RENDER_H, object : VlcPlayer.Listener {
            override fun onError(message: String) = scheduleReconnect(message)
            override fun onEnded() = scheduleReconnect("stream ended")
        })
        player = p
        p.play(config.url, config.useTcp)
    }

    private fun scheduleReconnect(reason: String) {
        if (!running) return
        releasePlayer()
        setStatus(Status.RECONNECTING, "$reason – retry in ${retryDelayMs / 1000}s")
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, retryDelayMs)
        retryDelayMs = min(retryDelayMs * 2, MAX_RETRY_MS)
    }

    private val reconnect = Runnable { if (running) connect() }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    /** Decoded frames arriving = alive. Marks LIVE, and reconnects on freeze / no video. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val src = source
            val now = SystemClock.elapsedRealtime()
            if (src != null && player != null) {
                val f = src.frames
                if (f > lastFrames) {
                    lastFrames = f
                    lastProgressAt = now
                    if (status != Status.LIVE) {
                        retryDelayMs = INITIAL_RETRY_MS
                        setStatus(Status.LIVE, null)
                    }
                } else if (status == Status.LIVE && now - lastProgressAt > FREEZE_TIMEOUT_MS) {
                    scheduleReconnect("stream frozen")
                } else if (status != Status.LIVE && now - connectedAt > CONNECT_TIMEOUT_MS) {
                    scheduleReconnect(src.error ?: "no video")
                }
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun setStatus(s: Status, message: String?) {
        status = s
        if (Looper.myLooper() == Looper.getMainLooper()) listener.onStatus(config, s, message)
        else main.post { listener.onStatus(config, s, message) }
    }

    // ---------------------------------------------------------------- frame analysis (source thread)

    private val frameLoop = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                analyseFrame()
            } catch (_: Exception) {
                // never let a single bad frame kill the loop
            }
            source?.handler?.postDelayed(this, FRAME_LOOP_MS)
        }
    }

    private fun analyseFrame() {
        val src = source ?: return
        if (status != Status.LIVE) return
        val now = SystemClock.elapsedRealtime()

        if (thumbnails && now - lastThumbAt >= THUMB_MS) {
            lastThumbAt = now
            src.grab(THUMB_W, THUMB_H)?.let { bmp -> main.post { listener.onThumbnail(config, bmp) } }
        }

        // Motion is always tracked (also needed for the "moving vehicles only" filter).
        val small = src.grab(MotionDetector.W, MotionDetector.H, smallFrame)
        smallFrame = small
        if (small != null && motion.update(small, config, now)) lastMotionAt = now
        val moving = config.motionSensitivity == 0 || now - lastMotionAt < MOTION_HOLD_MS

        // Fast scans while something moves. Without "moving only", slow idle scans also read
        // vehicles that stop at the gate; with it, still pictures are not scanned at all.
        if (!moving && movingOnly) return
        val interval = if (moving) ocrIntervalMs else IDLE_SCAN_MS
        if (ocrBusy || now - lastOcrAt < interval) return
        lastOcrAt = now

        val timestamp = System.currentTimeMillis()
        val full = src.grab(RENDER_W, RENDER_H) ?: return
        val roi = roiRect(RENDER_W, RENDER_H)
        val crop = if (roi.width() == RENDER_W && roi.height() == RENDER_H) full
        else Bitmap.createBitmap(full, roi.left, roi.top, roi.width(), roi.height())
        if (crop !== full) full.recycle()

        ocrBusy = true
        // Zoomed-tile fallback costs ~5 extra OCR passes: use it on every 2nd scan.
        val useTiles = (ocrCycle % 2 == 0)
        ocrCycle++
        scans++
        val n = scans
        main.post { listener.onScan(config, n, moving) }
        val grabbedAt = now
        val cw = crop.width.toFloat()
        val ch = crop.height.toFloat()
        recognizer.recognizePlates(crop, tilesFallback = useTiles) { result ->
            val candidates = if (!movingOnly) result.candidates else result.candidates.filter { c ->
                // plate must be where something moved in the last few seconds (a parked plate never does)
                motion.movedNear(c.box.left / cw, c.box.top / ch, c.box.right / cw, c.box.bottom / ch, grabbedAt)
            }
            ocrBusy = false
            if (candidates.isEmpty()) crop.recycle()
            else listener.onPlates(config, candidates, crop, timestamp)
        }
    }

    private fun roiRect(w: Int, h: Int): Rect {
        val l = (w * config.roiLeft.coerceIn(0, 95) / 100)
        val t = (h * config.roiTop.coerceIn(0, 95) / 100)
        val r = max(l + 16, w * config.roiRight.coerceIn(5, 100) / 100).coerceAtMost(w)
        val b = max(t + 16, h * config.roiBottom.coerceIn(5, 100) / 100).coerceAtMost(h)
        return Rect(l, t, r, b)
    }

    /** Cheap frame-difference motion detector on a 128×72 thumbnail of the ROI. */
    private class MotionDetector {
        private val pixels = IntArray(W * H)
        private var prev: IntArray? = null
        private var cur: IntArray? = null

        /** When each thumbnail pixel (inside the search area) last changed. */
        private val lastChange = LongArray(W * H)
        @Volatile private var rx0 = 0; @Volatile private var ry0 = 0
        @Volatile private var rx1 = W; @Volatile private var ry1 = H

        /**
         * True if something moved near this box (0..1 of the search area) within the last few
         * seconds before [atMs] – i.e. the plate belongs to a moving vehicle.
         */
        fun movedNear(l: Float, t: Float, r: Float, b: Float, atMs: Long): Boolean {
            val w = rx1 - rx0; val h = ry1 - ry0
            if (w <= 0 || h <= 0) return true
            val padX = (r - l) * 0.5f; val padY = (b - t) * 1.0f
            val x0 = (rx0 + ((l - padX) * w).toInt()).coerceIn(rx0, rx1 - 1)
            val x1 = (rx0 + ((r + padX) * w).toInt()).coerceIn(x0 + 1, rx1)
            val y0 = (ry0 + ((t - padY) * h).toInt()).coerceIn(ry0, ry1 - 1)
            val y1 = (ry0 + ((b + padY) * h).toInt()).coerceIn(y0 + 1, ry1)
            var hits = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val tc = lastChange[y * W + x]
                if (tc != 0L && atMs - tc in -1_000L..MOVE_WINDOW_MS) hits++
            }
            return hits >= 2
        }

        fun update(bmp: Bitmap, cfg: CameraConfig, now: Long): Boolean {
            bmp.getPixels(pixels, 0, W, 0, 0, W, H)
            val x0 = W * cfg.roiLeft.coerceIn(0, 95) / 100
            val y0 = H * cfg.roiTop.coerceIn(0, 95) / 100
            val x1 = max(x0 + 1, W * cfg.roiRight.coerceIn(5, 100) / 100)
            val y1 = max(y0 + 1, H * cfg.roiBottom.coerceIn(5, 100) / 100)
            val n = (x1 - x0) * (y1 - y0)

            var c = cur
            if (c == null || c.size != n) { c = IntArray(n); prev = null }
            var k = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val p = pixels[y * W + x]
                c[k++] = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            }
            val p = prev
            // swap buffers
            cur = p ?: IntArray(n)
            prev = c
            if (p == null || p.size != n) return false

            val rw = x1 - x0
            val rh = y1 - y0
            var changed = 0
            var minX = rw; var minY = rh; var maxX = -1; var maxY = -1
            rx0 = x0; ry0 = y0; rx1 = x1; ry1 = y1
            for (i in 0 until n) if (abs(c[i] - p[i]) > PIXEL_DIFF) {
                changed++
                lastChange[(y0 + i / rw) * W + (x0 + i % rw)] = now
                val x = i % rw
                val y = i / rw
                // ignore isolated noise pixels for the bounding box
                val right = i + 1 < n && x + 1 < rw && abs(c[i + 1] - p[i + 1]) > PIXEL_DIFF
                val below = i + rw < n && abs(c[i + rw] - p[i + rw]) > PIXEL_DIFF
                if (right || below) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
            }
            val threshold = when (cfg.motionSensitivity) { 1 -> 0.03f; 2 -> 0.012f; else -> 0.005f }
            val moving = changed.toFloat() / n > threshold
            box = if (moving && maxX >= 0)
                RectF(minX.toFloat() / rw, minY.toFloat() / rh, (maxX + 1).toFloat() / rw, (maxY + 1).toFloat() / rh)
            else null
            return moving
        }

        /** Where the motion was in the last [update], as 0..1 of the region of interest. */
        var box: RectF? = null
            private set

        companion object {
            const val W = 128
            const val H = 72
            const val PIXEL_DIFF = 25
            const val MOVE_WINDOW_MS = 3_000L
        }
    }

    companion object {
        /** Off-screen decode size – what plate reading sees. */
        const val RENDER_W = 1920
        const val RENDER_H = 1080
        private const val THUMB_W = 480
        private const val THUMB_H = 270
        private const val THUMB_MS = 1_000L
        private const val FRAME_LOOP_MS = 150L
        private const val MOTION_HOLD_MS = 2_500L
        private const val IDLE_SCAN_MS = 1_500L
        private const val INITIAL_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 30_000L
        private const val WATCHDOG_MS = 2_000L
        private const val FREEZE_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
