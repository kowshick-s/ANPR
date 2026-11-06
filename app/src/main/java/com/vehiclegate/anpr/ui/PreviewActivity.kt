package com.vehiclegate.anpr.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import com.vehiclegate.anpr.core.RtspProbe
import com.vehiclegate.anpr.data.CameraConfig
import com.vehiclegate.anpr.databinding.ActivityPreviewBinding
import com.vehiclegate.anpr.ocr.PlateRecognizer
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * On-demand full-screen preview of ONE camera, opened from Settings. Shows the live picture,
 * stream details (codec, resolution, fps, dropped frames, time to first frame), the plate
 * search area, and lets you run OCR on the current frame to check the camera is positioned well.
 * Nothing runs in the background – the stream is closed when you leave this screen.
 */
@OptIn(UnstableApi::class)
class PreviewActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CAMERA = "camera"
        fun start(context: Context, cam: CameraConfig) {
            context.startActivity(Intent(context, PreviewActivity::class.java)
                .putExtra(EXTRA_CAMERA, cam.toJson().toString()))
        }
    }

    private lateinit var b: ActivityPreviewBinding
    private lateinit var cam: CameraConfig
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var recognizer: PlateRecognizer? = null

    private var useTcp = true
    private var showRoi = true
    private var connectStartedAt = 0L
    private var firstFrameMs = -1L
    private var state = "Connecting…"
    private var lastError: String? = null
    private var videoW = 0
    private var videoH = 0
    private var lastRendered = -1
    private var fps = 0
    private var reconnects = 0
    @Volatile private var ocrBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(b.root)

        cam = CameraConfig.fromJson(JSONObject(intent.getStringExtra(EXTRA_CAMERA) ?: "{}"))
        useTcp = cam.useTcp
        b.camTitle.text = cam.name
        b.aspect.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        b.overlay.setRoi(cam.roiLeft, cam.roiTop, cam.roiRight, cam.roiBottom)

        b.btnReadPlate.setOnClickListener { readPlateNow() }
        b.btnTest.setOnClickListener { RtspTestDialog.show(this, cam.name, cam.url) }
        b.btnReconnect.setOnClickListener { reconnects++; connect() }
        b.btnTransport.setOnClickListener {
            useTcp = !useTcp
            updateTransportLabel()
            connect()
        }
        b.btnRoi.setOnClickListener {
            showRoi = !showRoi
            b.overlay.setShowRoi(showRoi)
            b.btnRoi.text = if (showRoi) "Hide search area" else "Show search area"
        }
        b.btnClose.setOnClickListener { finish() }
        updateTransportLabel()
        b.btnReadPlate.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        recognizer = PlateRecognizer()
        connect()
        handler.post(statsTick)
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        recognizer?.close()
        recognizer = null
        super.onStop()
    }

    private fun updateTransportLabel() {
        b.btnTransport.text = "Transport: ${if (useTcp) "TCP" else "UDP"}" +
            if (useTcp != cam.useTcp) " (not saved)" else ""
    }

    // ---------------------------------------------------------------- playback

    private fun connect() {
        releasePlayer()
        state = "Connecting…"
        lastError = null
        firstFrameMs = -1
        lastRendered = -1
        fps = 0
        connectStartedAt = SystemClock.elapsedRealtime()
        b.overlay.setBoxes(emptyList())
        showCenter("Connecting to ${cam.name}…\n${RtspTestDialog.maskPassword(cam.url)}")

        val p = ExoPlayer.Builder(this)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(500, 2_000, 250, 250)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            ).build()
        p.volume = 0f
        p.setVideoTextureView(b.video)
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoW = videoSize.width
                    videoH = videoSize.height
                    b.aspect.setAspectRatio(videoW * videoSize.pixelWidthHeightRatio / videoH)
                }
            }

            override fun onRenderedFirstFrame() {
                firstFrameMs = SystemClock.elapsedRealtime() - connectStartedAt
                state = "LIVE"
                showCenter(null)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> if (firstFrameMs < 0) state = "Buffering…"
                    Player.STATE_ENDED -> { state = "Stream ended"; showCenter("Stream ended.\nPress Reconnect.") }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                state = "ERROR"
                lastError = "${error.errorCodeName.removePrefix("ERROR_CODE_")}: ${error.cause?.message ?: error.message}"
                showCenter("Cannot play this stream.\n$lastError\n\nPress \"Connection test\" to see which step fails.")
                b.btnTest.requestFocus()
            }
        })
        p.setMediaSource(
            RtspMediaSource.Factory()
                .setForceUseRtpTcp(useTcp)
                .setTimeoutMs(8_000)
                .createMediaSource(MediaItem.fromUri(RtspProbe.normalizeUrl(cam.url)))
        )
        p.playWhenReady = true
        p.prepare()
        player = p
    }

    private fun releasePlayer() {
        player?.let { it.clearVideoTextureView(b.video); it.release() }
        player = null
    }

    private fun showCenter(msg: String?) {
        b.centerStatus.text = msg ?: ""
        b.centerStatus.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    /** Refreshes the info panel once a second. */
    private val statsTick = object : Runnable {
        override fun run() {
            val p = player
            val sb = StringBuilder()
            sb.append("Status     : $state\n")
            sb.append("URL        : ${RtspTestDialog.maskPassword(cam.url)}\n")
            if (runCatching { RtspProbe.parseUrl(cam.url).warning }.getOrNull() != null)
                sb.append("URL fix    : special characters in user/password encoded\n")
            sb.append("Transport  : ${if (useTcp) "TCP (interleaved)" else "UDP"}\n")
            if (p != null) {
                val f = p.videoFormat
                if (f != null) {
                    val mime = (f.sampleMimeType ?: "?").removePrefix("video/").uppercase()
                    sb.append("Codec      : $mime ${f.codecs ?: ""}\n")
                }
                if (videoW > 0) sb.append("Resolution : ${videoW}x$videoH" +
                    (if (videoW < 1280) "  ⚠ low for plates – use main stream" else "") + "\n")
                p.videoDecoderCounters?.let { c ->
                    c.ensureUpdated()
                    if (lastRendered >= 0) fps = c.renderedOutputBufferCount - lastRendered
                    lastRendered = c.renderedOutputBufferCount
                    sb.append("Frame rate : $fps fps   dropped: ${c.droppedBufferCount}\n")
                }
            }
            if (firstFrameMs >= 0) sb.append("First frame: ${firstFrameMs} ms after connect\n")
            else sb.append("Waiting    : ${(SystemClock.elapsedRealtime() - connectStartedAt) / 1000}s\n")
            sb.append("Search area: L${cam.roiLeft}% T${cam.roiTop}% R${cam.roiRight}% B${cam.roiBottom}%")
            lastError?.let { sb.append("\nError      : $it") }
            b.info.text = sb.toString()
            handler.postDelayed(this, 1000)
        }
    }

    // ---------------------------------------------------------------- read plate now

    private fun readPlateNow() {
        val rec = recognizer ?: return
        if (state != "LIVE" || videoW <= 0 || !b.video.isAvailable) {
            showOcr("No video yet – wait for the picture to appear.", ok = false); return
        }
        if (ocrBusy) return
        val scale = min(1f, 1920f / videoW)
        val fw = max(2, (videoW * scale).toInt())
        val fh = max(2, (videoH * scale).toInt())
        val full = b.video.getBitmap(fw, fh) ?: return
        val l = fw * cam.roiLeft.coerceIn(0, 95) / 100
        val t = fh * cam.roiTop.coerceIn(0, 95) / 100
        val r = max(l + 16, fw * cam.roiRight.coerceIn(5, 100) / 100).coerceAtMost(fw)
        val bt = max(t + 16, fh * cam.roiBottom.coerceIn(5, 100) / 100).coerceAtMost(fh)
        val crop = if (l == 0 && t == 0 && r == fw && bt == fh) full
        else Bitmap.createBitmap(full, l, t, r - l, bt - t).also { full.recycle() }

        ocrBusy = true
        showOcr("Reading…", ok = true)
        val started = SystemClock.elapsedRealtime()
        rec.recognize(crop) { cands ->
            val ms = SystemClock.elapsedRealtime() - started
            crop.recycle()
            runOnUiThread {
                ocrBusy = false
                if (cands.isEmpty()) {
                    b.overlay.setBoxes(emptyList())
                    showOcr("No number plate found in this frame ($ms ms).\n" +
                        "Try when a vehicle is in front of the camera, or adjust the search area / zoom / focus.", ok = false)
                } else {
                    b.overlay.setBoxes(cands.take(5).map {
                        RoiOverlayView.Box(
                            RectF((l + it.box.left).toFloat() / fw, (t + it.box.top).toFloat() / fh,
                                (l + it.box.right).toFloat() / fw, (t + it.box.bottom).toFloat() / fh),
                            it.plate.formatted
                        )
                    })
                    showOcr(cands.take(5).joinToString("\n") {
                        val w = it.box.width()
                        "${it.plate.formatted.padEnd(15)} score ${(it.score * 100).toInt()}%  " +
                            "OCR ${(it.confidence * 100).toInt()}%  plate ${w}px wide" +
                            if (w < 130) "  ⚠ small" else ""
                    } + "\n($ms ms)", ok = true)
                }
            }
        }
    }

    private fun showOcr(msg: String, ok: Boolean) {
        b.ocrResult.visibility = View.VISIBLE
        b.ocrResult.text = msg
        b.ocrResult.setTextColor(if (ok) 0xFF00E676.toInt() else 0xFFFFB300.toInt())
    }
}
