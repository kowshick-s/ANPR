package com.vehiclegate.anpr.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
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
@OptIn(UnstableApi::class)
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
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var running = false
    private var status = Status.STOPPED
    private var retryDelayMs = INITIAL_RETRY_MS

    private var videoW = 0
    private var videoH = 0

    private val motion = MotionDetector()
    private val smallFrame: Bitmap = Bitmap.createBitmap(MotionDetector.W, MotionDetector.H, Bitmap.Config.ARGB_8888)
    private var lastMotionAt = 0L
    private var lastOcrAt = 0L
    @Volatile private var ocrBusy = false

    // Freeze watchdog
    private var lastPosition = -1L
    private var lastProgressAt = 0L

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

    // ---------------------------------------------------------------- playback

    private fun connect() {
        releasePlayer()
        setStatus(if (retryDelayMs > INITIAL_RETRY_MS) Status.RECONNECTING else Status.CONNECTING, null)

        // Tiny buffers = low latency; we want "now", not smooth playback.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 2_000, 250, 250)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val p = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
        p.volume = 0f
        p.setVideoTextureView(textureView)
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoW = videoSize.width
                videoH = videoSize.height
            }

            override fun onRenderedFirstFrame() {
                retryDelayMs = INITIAL_RETRY_MS
                lastProgressAt = SystemClock.elapsedRealtime()
                setStatus(Status.LIVE, if (videoW > 0) "${videoW}x$videoH" else null)
            }

            override fun onPlayerError(error: PlaybackException) {
                scheduleReconnect(describe(error))
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) scheduleReconnect("stream ended")
            }
        })

        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(config.useTcp)
            .setTimeoutMs(RTSP_TIMEOUT_MS)
            .createMediaSource(MediaItem.fromUri(config.url.trim()))
        p.setMediaSource(source)
        p.playWhenReady = true
        p.prepare()
        player = p
        lastPosition = -1
        lastProgressAt = SystemClock.elapsedRealtime()
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
        player?.let {
            it.clearVideoTextureView(textureView)
            it.release()
        }
        player = null
    }

    /** RTSP sources can silently freeze; restart if playback position stops moving. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val p = player
            val now = SystemClock.elapsedRealtime()
            if (p != null && status == Status.LIVE) {
                val pos = p.currentPosition
                if (pos != lastPosition) {
                    lastPosition = pos
                    lastProgressAt = now
                } else if (now - lastProgressAt > FREEZE_TIMEOUT_MS) {
                    scheduleReconnect("stream frozen")
                }
            } else if (p != null && status != Status.LIVE && now - lastProgressAt > CONNECT_TIMEOUT_MS) {
                scheduleReconnect("no video")
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun setStatus(s: Status, message: String?) {
        status = s
        listener.onStatus(config, s, message)
    }

    private fun describe(e: PlaybackException): String {
        val cause = e.cause?.message ?: e.message ?: ""
        return when {
            cause.contains("401") || cause.contains("Unauthorized", true) -> "authentication failed"
            cause.contains("404") -> "stream path not found"
            else -> e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
        }
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
        if (status != Status.LIVE || !textureView.isAvailable) return
        val now = SystemClock.elapsedRealtime()

        val moving = if (config.motionSensitivity == 0) {
            true
        } else {
            textureView.getBitmap(smallFrame)
            if (motion.update(smallFrame, config)) lastMotionAt = now
            now - lastMotionAt < MOTION_HOLD_MS
        }
        if (!moving || ocrBusy || now - lastOcrAt < ocrIntervalMs) return
        if (videoW <= 0 || videoH <= 0) return
        lastOcrAt = now

        // Grab at native resolution (capped at 1920 wide) regardless of the tile's size on screen.
        val scale = min(1f, MAX_OCR_FRAME_WIDTH.toFloat() / videoW)
        val fw = max(2, (videoW * scale).toInt())
        val fh = max(2, (videoH * scale).toInt())
        val timestamp = System.currentTimeMillis()
        val full = textureView.getBitmap(fw, fh) ?: return

        val roi = roiRect(fw, fh)
        val crop = if (roi.width() == fw && roi.height() == fh) full
        else Bitmap.createBitmap(full, roi.left, roi.top, roi.width(), roi.height())
        if (crop !== full) full.recycle()

        ocrBusy = true
        recognizer.recognize(crop) { candidates ->
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

            var changed = 0
            for (i in 0 until n) if (abs(c[i] - p[i]) > PIXEL_DIFF) changed++
            val threshold = when (cfg.motionSensitivity) { 1 -> 0.03f; 2 -> 0.012f; else -> 0.005f }
            return changed.toFloat() / n > threshold
        }

        companion object {
            const val W = 128
            const val H = 72
            const val PIXEL_DIFF = 25
        }
    }

    companion object {
        private const val FRAME_LOOP_MS = 150L
        private const val MOTION_HOLD_MS = 2_500L
        private const val MAX_OCR_FRAME_WIDTH = 1920
        private const val RTSP_TIMEOUT_MS = 8_000L
        private const val INITIAL_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 30_000L
        private const val WATCHDOG_MS = 3_000L
        private const val FREEZE_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
