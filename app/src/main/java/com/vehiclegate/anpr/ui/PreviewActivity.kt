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
import androidx.appcompat.app.AppCompatActivity
import com.vehiclegate.anpr.camera.VlcPlayer
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
    private var player: VlcPlayer? = null
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
    private var zoomed = false
    private var roiBeforeEdit = RectF(0f, 0f, 1f, 1f)
    @Volatile private var ocrBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(b.root)

        cam = CameraConfig.fromJson(JSONObject(intent.getStringExtra(EXTRA_CAMERA) ?: "{}"))
        useTcp = cam.useTcp
        b.camTitle.text = cam.name
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
        b.btnEditArea.setOnClickListener { startEditArea() }
        b.btnZoom.setOnClickListener { setZoom(!zoomed) }
        b.btnSaveArea.setOnClickListener { saveArea() }
        b.btnFullArea.setOnClickListener { b.overlay.setRoi(RectF(0f, 0f, 1f, 1f)) }
        b.btnCancelArea.setOnClickListener { cancelEditArea() }
        b.overlay.listener = object : RoiOverlayView.Listener {
            override fun onRoiChanged(roi: RectF) { if (zoomed) applyZoom() }
        }
        b.aspect.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (zoomed) applyZoom() }
        updateTransportLabel()
        b.btnReadPlate.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        recognizer = PlateRecognizer(this, (application as com.vehiclegate.anpr.AnprApp).settingsStore.settings().useAiPlates)
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

        val p = VlcPlayer(this, b.video, object : VlcPlayer.Listener {
            override fun onVideoSize(width: Int, height: Int) {
                videoW = width
                videoH = height
                b.aspect.setAspectRatio(width.toFloat() / height)
            }

            override fun onFirstFrame() {
                firstFrameMs = SystemClock.elapsedRealtime() - connectStartedAt
                state = "LIVE"
                showCenter(null)
            }

            override fun onBuffering(percent: Float) {
                if (firstFrameMs < 0) state = "Buffering… ${percent.toInt()}%"
            }

            override fun onEnded() {
                state = "Stream ended"
                showCenter("Stream ended / connection lost.\nPress Reconnect.")
            }

            override fun onError(message: String) {
                state = "ERROR"
                lastError = message
                showCenter("Cannot play this stream.\n$message\n\nPress \"Connection test\" to see which step fails.")
                b.btnTest.requestFocus()
            }
        })
        player = p
        p.play(cam.url, useTcp)
    }

    private fun releasePlayer() {
        player?.release()
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
            sb.append("Player     : VLC engine (LibVLC)\n")
            sb.append("Transport  : ${if (useTcp) "TCP (interleaved)" else "UDP"}\n")
            if (p != null) {
                p.trackInfo()?.let { sb.append("Video      : $it\n") }
                if (videoW > 0) sb.append("Rendering  : ${videoW}x$videoH" +
                    (if (videoW < 1280) "  ⚠ low for plates – use main stream" else "") + "\n")
                p.stats()?.let { st ->
                    if (lastRendered >= 0) fps = st.displayedFrames - lastRendered
                    lastRendered = st.displayedFrames
                    sb.append("Frame rate : $fps fps   lost: ${st.lostFrames}\n")
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
        player?.refreshSize()
        if (state != "LIVE") {
            showOcr("No video yet – wait for the picture to appear. (state: $state)", ok = false); return
        }
        if (ocrBusy) return
        // Native size if known; otherwise 1920 wide with the on-screen aspect ratio.
        val vw = if (videoW > 0) videoW else 1920
        val vh = if (videoH > 0) videoH else
            (1920f * b.video.height.coerceAtLeast(1) / b.video.width.coerceAtLeast(1)).toInt()
        val scale = min(1f, 1920f / vw)
        val fw = max(2, (vw * scale).toInt())
        val fh = max(2, (vh * scale).toInt())
        val full = try { b.video.getBitmap(fw, fh) } catch (e: Exception) { null }
        if (full == null) {
            showOcr("Could not grab a frame from the video (available=${b.video.isAvailable}, " +
                "size=${videoW}x$videoH, view=${b.video.width}x${b.video.height}). Press Reconnect and try again.", ok = false)
            return
        }
        val l = fw * cam.roiLeft.coerceIn(0, 95) / 100
        val t = fh * cam.roiTop.coerceIn(0, 95) / 100
        val r = max(l + 16, fw * cam.roiRight.coerceIn(5, 100) / 100).coerceAtMost(fw)
        val bt = max(t + 16, fh * cam.roiBottom.coerceIn(5, 100) / 100).coerceAtMost(fh)
        val crop = if (l == 0 && t == 0 && r == fw && bt == fh) full
        else Bitmap.createBitmap(full, l, t, r - l, bt - t).also { full.recycle() }

        ocrBusy = true
        showOcr("Finding plates… then straightening + enlarging each one for OCR", ok = true)
        clearDebug()
        val started = SystemClock.elapsedRealtime()
        rec.recognizePlates(crop, tilesFallback = true, keepDebug = true) { result ->
            val ms = SystemClock.elapsedRealtime() - started
            crop.recycle()
            runOnUiThread {
                ocrBusy = false
                fun toFrame(r: android.graphics.Rect) = RectF(
                    (l + r.left).toFloat() / fw, (t + r.top).toFloat() / fh,
                    (l + r.right).toFloat() / fw, (t + r.bottom).toFloat() / fh)
                b.overlay.setRegions(result.regions.map { toFrame(it) })
                b.overlay.setBoxes(result.candidates.take(5).map { RoiOverlayView.Box(toFrame(it.box), it.plate.formatted) })
                showDebug(result.debug)
                val found = "${result.regions.size} plate-like region(s) found (yellow)"
                if (result.candidates.isEmpty()) {
                    showOcr("No number plate read ($ms ms). $found.\n" +
                        "Tip: ✏ Set search area tightly around the lane/plates so they are enlarged more, " +
                        "and check the crops below – if the text in them is too blurry, the camera needs " +
                        "higher resolution, faster shutter or less distance.", ok = false)
                } else {
                    showOcr(result.candidates.take(5).joinToString("\n") {
                        "${it.plate.formatted.padEnd(15)} score ${(it.score * 100).toInt()}%  OCR ${(it.confidence * 100).toInt()}%"
                    } + "\n$found${if (result.usedTiles) ", read via zoomed tiles" else ""} · $ms ms", ok = true)
                }
            }
        }
    }

    private fun clearDebug() {
        b.debugStrip.removeAllViews()
        b.debugScroll.visibility = View.GONE
    }

    /** Shows each prepared plate crop with the raw OCR text, so you can see what the reader saw. */
    private fun showDebug(list: List<PlateRecognizer.DebugCrop>) {
        clearDebug()
        if (list.isEmpty()) return
        val dp = resources.displayMetrics.density
        for (dc in list.take(8)) {
            val col = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundResource(com.vehiclegate.anpr.R.drawable.label_bg)
                setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            }
            col.addView(android.widget.ImageView(this).apply {
                setImageBitmap(dc.image)
                adjustViewBounds = true
                maxHeight = (90 * dp).toInt()
                maxWidth = (220 * dp).toInt()
            })
            col.addView(android.widget.TextView(this).apply {
                text = (dc.plate?.let { "✔ $it" } ?: "✘ ${dc.text.ifBlank { "(no text)" }}").take(28)
                setTextColor(if (dc.plate != null) 0xFF00E676.toInt() else 0xFFFFB300.toInt())
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
            })
            val lp = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (6 * dp).toInt()
            b.debugStrip.addView(col, lp)
        }
        b.debugScroll.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- search area editing

    private fun startEditArea() {
        roiBeforeEdit = b.overlay.getRoi()
        b.overlay.editing = true
        b.overlay.resizeMode = false
        b.overlay.setBoxes(emptyList()); b.overlay.setRegions(emptyList()); clearDebug()
        if (zoomed) setZoom(false) // edit on the whole picture
        b.editBar.visibility = View.VISIBLE
        b.buttonBar.visibility = View.GONE
        b.infoPanel.visibility = View.GONE
        b.btnSaveArea.requestFocus()
    }

    private fun endEditArea() {
        b.overlay.editing = false
        b.editBar.visibility = View.GONE
        b.buttonBar.visibility = View.VISIBLE
        b.infoPanel.visibility = View.VISIBLE
        b.btnEditArea.requestFocus()
    }

    private fun cancelEditArea() {
        b.overlay.setRoi(roiBeforeEdit)
        endEditArea()
    }

    /** Saves the area to this camera permanently (kept after closing/restarting the app). */
    private fun saveArea() {
        val r = b.overlay.getRoi()
        val updated = cam.copy(
            roiLeft = (r.left * 100).toInt().coerceIn(0, 95),
            roiTop = (r.top * 100).toInt().coerceIn(0, 95),
            roiRight = Math.ceil(r.right * 100.0).toInt().coerceIn(5, 100),
            roiBottom = Math.ceil(r.bottom * 100.0).toInt().coerceIn(5, 100)
        )
        val store = (application as com.vehiclegate.anpr.AnprApp).settingsStore
        val list = store.cameras().toMutableList()
        val i = list.indexOfFirst { it.id == cam.id }
        cam = updated
        b.overlay.setRoi(cam.roiLeft, cam.roiTop, cam.roiRight, cam.roiBottom)
        if (i >= 0) {
            list[i] = updated
            store.saveCameras(list)
            android.widget.Toast.makeText(this,
                "Search area saved for ${cam.name}: L${cam.roiLeft}% T${cam.roiTop}% R${cam.roiRight}% B${cam.roiBottom}%",
                android.widget.Toast.LENGTH_LONG).show()
        } else {
            android.widget.Toast.makeText(this,
                "This camera is not saved yet – save it in Settings first. The area applies to this preview only.",
                android.widget.Toast.LENGTH_LONG).show()
        }
        endEditArea()
    }

    // TV remote while editing: arrows move/resize, OK toggles, BACK asks to save.
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (b.overlay.editing && event.action == android.view.KeyEvent.ACTION_DOWN) {
            val step = if (event.repeatCount > 3) 0.04f else 0.01f
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { b.overlay.nudge(-step, 0f); return true }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { b.overlay.nudge(step, 0f); return true }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> { b.overlay.nudge(0f, -step); return true }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { b.overlay.nudge(0f, step); return true }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
                    b.overlay.resizeMode = !b.overlay.resizeMode; return true
                }
                android.view.KeyEvent.KEYCODE_BACK -> {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Save this search area?")
                        .setMessage("It is kept for this camera until you change it again.")
                        .setPositiveButton("Save") { _, _ -> saveArea() }
                        .setNegativeButton("Discard") { _, _ -> cancelEditArea() }
                        .setNeutralButton("Keep editing", null)
                        .show()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- zoom (view only)

    /** Enlarges the picture so the search area fills the screen (display only – OCR always uses full detail). */
    private fun setZoom(on: Boolean) {
        zoomed = on
        b.btnZoom.text = if (on) "⛶ Full view" else "⛶ Zoom to area"
        if (on) applyZoom() else {
            b.aspect.scaleX = 1f; b.aspect.scaleY = 1f
            b.aspect.translationX = 0f; b.aspect.translationY = 0f
        }
    }

    private fun applyZoom() {
        val v = b.aspect
        val parent = v.parent as View
        if (v.width == 0 || v.height == 0) return
        val r = b.overlay.getRoi()
        val scale = min(1f / r.width().coerceAtLeast(0.05f), 1f / r.height().coerceAtLeast(0.05f)).coerceIn(1f, 6f)
        v.pivotX = 0f; v.pivotY = 0f
        v.scaleX = scale; v.scaleY = scale
        v.translationX = parent.width / 2f - v.left - r.centerX() * v.width * scale
        v.translationY = parent.height / 2f - v.top - r.centerY() * v.height * scale
    }

    private fun showOcr(msg: String, ok: Boolean) {
        b.ocrResult.visibility = View.VISIBLE
        b.ocrResult.text = msg
        b.ocrResult.setTextColor(if (ok) 0xFF00E676.toInt() else 0xFFFFB300.toInt())
    }
}
