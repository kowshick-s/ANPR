package com.vehiclegate.anpr.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import com.vehiclegate.anpr.data.CameraConfig
import com.vehiclegate.anpr.ocr.PlateRecognizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One RTSP camera: plays the stream into a [TextureView] (which is also the live tile on the TV),
 * watches for motion inside the region of interest, and while something is moving grabs
 * full-resolution frames and sends them to the [PlateRecognizer].
 *
 * Auto-reconnects with back-off when the camera/NVR drops the stream or the stream freezes.
 * All methods must be called on the main thread.
 */
class CameraStream(
    private val context: Context,
    val config: CameraConfig,
    private val textureView: TextureView,
    private val recognizer: PlateRecognizer,
    private val ocrIntervalMs: Long,
    private val listener: Listener
) {
    enum class Status { CONNECTING, LIVE, RECONNECTING, STOPPED }

    interface Listener {
        fun onStatus(camera: CameraConfig, status: Status, message: String?)

        /**
         * Called on a background thread. [frame] is the ROI image the plates were found in
         * (box coordinates refer to it); ownership passes to the listener.
         */
        fun onPlates(camera: CameraConfig, candidates: List<PlateRecognizer.Candidate>, frame: Bitmap, timestampMs: Long)

        /** A frame was sent for plate reading (main thread) – for the dashboard's scan counter. */
        fun onScan(camera: CameraConfig, scans: Int, motion: Boolean) {}
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: VlcPlayer? = null
    private var running = false
    private var status = Status.STOPPED
    private var retryDelayMs = INITIAL_RETRY_MS

    private var videoW = 0
    private var videoH = 0

    private val motion = MotionDetector()
    private val smallFrame: Bitmap = Bitmap.createBitmap(MotionDetector.W, MotionDetector.H, Bitmap.Config.ARGB_8888)
    private var lastMotionAt = 0L
    private var ocrCycle = 0
    private var scans = 0
    private var lastOcrAt = 0L
    @Volatile private var ocrBusy = false

    // Watchdog
    private var connectedAt = 0L
    private var lastProgressAt = 0L
    private var lastDisplayed = -1

    fun start() {
        if (running) return
        running = true
        connect()
        handler.postDelayed(frameLoop, FRAME_LOOP_MS)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        setStatus(Status.STOPPED, null)
    }

    // ---------------------------------------------------------------- playback (LibVLC)

    private fun connect() {
        releasePlayer()
        setStatus(if (retryDelayMs > INITIAL_RETRY_MS) Status.RECONNECTING else Status.CONNECTING, null)
        connectedAt = SystemClock.elapsedRealtime()
        lastProgressAt = connectedAt
        lastDisplayed = -1

        val p = VlcPlayer(context, textureView, object : VlcPlayer.Listener {
            override fun onVideoSize(width: Int, height: Int) {
                videoW = width
                videoH = height
                if (status == Status.LIVE) setStatus(Status.LIVE, "${width}x$height")
            }

            override fun onFirstFrame() {
                retryDelayMs = INITIAL_RETRY_MS
                lastProgressAt = SystemClock.elapsedRealtime()
                setStatus(Status.LIVE, if (videoW > 0) "${videoW}x$videoH" else null)
            }

            override fun onProgress() {
                lastProgressAt = SystemClock.elapsedRealtime()
            }

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
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, retryDelayMs)
        retryDelayMs = min(retryDelayMs * 2, MAX_RETRY_MS)
    }

    private val reconnect = Runnable { if (running) connect() }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    /** RTSP sources can silently freeze; restart if the playback clock stops advancing. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            // Displayed-frame counter is the most reliable "alive" signal for live RTSP.
            player?.stats()?.displayedFrames?.let { d ->
                if (d != lastDisplayed) { lastDisplayed = d; lastProgressAt = now }
            }
            if (player != null) {
                if (status == Status.LIVE && now - lastProgressAt > FREEZE_TIMEOUT_MS) {
                    scheduleReconnect("stream frozen")
                } else if (status != Status.LIVE && now - connectedAt > CONNECT_TIMEOUT_MS) {
                    scheduleReconnect("no video")
                }
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun setStatus(s: Status, message: String?) {
        status = s
        listener.onStatus(config, s, message)
    }

    // ---------------------------------------------------------------- frame analysis

    private val frameLoop = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                analyseFrame()
            } catch (_: Exception) {
                // never let a single bad frame kill the loop
            }
            handler.postDelayed(this, FRAME_LOOP_MS)
        }
    }

    private fun analyseFrame() {
        if (status != Status.LIVE) return
        val now = SystemClock.elapsedRealtime()

        val moving = if (config.motionSensitivity == 0) {
            true
        } else {
            textureView.getBitmap(smallFrame)
            if (motion.update(smallFrame, config)) lastMotionAt = now
            now - lastMotionAt < MOTION_HOLD_MS
        }
        // Fast scans while something moves; slow "idle" scans otherwise, so vehicles that stop
        // at the gate (or creep in without much motion) are still read.
        val interval = if (moving) ocrIntervalMs else IDLE_SCAN_MS
        if (ocrBusy || now - lastOcrAt < interval) return
        if (videoW <= 0) player?.refreshSize()
        lastOcrAt = now

        // Grab at native resolution (capped at 1920 wide) regardless of the tile's size on screen.
        // If the resolution is still unknown, fall back to 1920×1080.
        val vw = if (videoW > 0) videoW else 1920
        val vh = if (videoH > 0) videoH else 1080
        val scale = min(1f, MAX_OCR_FRAME_WIDTH.toFloat() / vw)
        val fw = max(2, (vw * scale).toInt())
        val fh = max(2, (vh * scale).toInt())
        val timestamp = System.currentTimeMillis()
        val full = textureView.getBitmap(fw, fh) ?: return

        val roi = roiRect(fw, fh)
        val crop = if (roi.width() == fw && roi.height() == fh) full
        else Bitmap.createBitmap(full, roi.left, roi.top, roi.width(), roi.height())
        if (crop !== full) full.recycle()

        ocrBusy = true
        // Plate-first: locate plates, straighten + enlarge each, OCR. Every 3rd cycle also fall
        // back to zoomed tiles when no plate region was readable.
        val roiArea = (config.roiRight - config.roiLeft) * (config.roiBottom - config.roiTop) / 10_000f
        val useTiles = roiArea <= 0.35f || (ocrCycle % 2 == 0) // small area = cheap, always zoom
        ocrCycle++
        scans++
        listener.onScan(config, scans, moving)
        recognizer.recognizePlates(crop, tilesFallback = useTiles) { result ->
            val candidates = result.candidates
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

        fun update(bmp: Bitmap, cfg: CameraConfig): Boolean {
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
            for (i in 0 until n) if (abs(c[i] - p[i]) > PIXEL_DIFF) {
                changed++
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
        }
    }

    companion object {
        private const val FRAME_LOOP_MS = 150L
        private const val MOTION_HOLD_MS = 2_500L
        private const val IDLE_SCAN_MS = 1_500L
        private const val MAX_OCR_FRAME_WIDTH = 1920
        private const val INITIAL_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 30_000L
        private const val WATCHDOG_MS = 3_000L
        private const val FREEZE_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
