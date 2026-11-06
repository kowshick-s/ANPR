package com.vehiclegate.anpr.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.vehiclegate.anpr.AnprApp
import com.vehiclegate.anpr.AnprPipeline
import com.vehiclegate.anpr.R
import com.vehiclegate.anpr.camera.CameraStream
import com.vehiclegate.anpr.data.AppSettings
import com.vehiclegate.anpr.data.CameraConfig
import com.vehiclegate.anpr.data.VehicleEntry
import com.vehiclegate.anpr.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The TV dashboard: latest vehicle (big plate + date + entry time + snapshot + per-camera
 * comparison), recent entries list, and live tiles for every camera.
 */
class MainActivity : AppCompatActivity(), AnprPipeline.Events {

    companion object {
        /** Off-screen render size of each camera (what plate reading sees). */
        private const val RENDER_W = 1920
        private const val RENDER_H = 1080
    }

    private lateinit var b: ActivityMainBinding
    private val app get() = application as AnprApp
    private lateinit var pipeline: AnprPipeline
    private val adapter = EntryAdapter()
    private val handler = Handler(Looper.getMainLooper())

    private class Tile(val root: View, val video: TextureView, val dot: ImageView,
                       val status: TextView, val lastRead: TextView,
                       val name: TextView, val camName: String, val compact: Boolean) {
        var statusLabel = ""
        var scans = 0
        var reads = 0
        var motion = false
        fun refreshName() {
            name.text = buildString {
                append(camName)
                if (statusLabel.isNotEmpty()) append(" · ").append(statusLabel)
                if (scans > 0) append(" · ").append(if (motion) "motion" else "idle").append(" · ").append(scans).append(" scans")
                if (scans > 0) append(" · ").append(reads).append(" reads")
            }
        }
        val hideRead = Runnable { lastRead.visibility = View.GONE }
    }
    private val tiles = HashMap<String, Tile>()

    private var settings = AppSettings()
    private var countJob: Job? = null
    private var countDayStart = 0L
    private var shownLatestId = -1L
    private var tone: ToneGenerator? = null

    private val clockFmt = SimpleDateFormat("EEE, dd MMM yyyy  hh:mm:ss a", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("dd MMM yyyy, EEEE", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        pipeline = AnprPipeline(app, lifecycleScope, this)

        b.entries.layoutManager = LinearLayoutManager(this)
        b.entries.adapter = adapter
        b.entries.itemAnimator = null
        b.settingsButton.setOnClickListener { openSettings() }

        lifecycleScope.launch {
            app.db.entries().recent(50).collect { list ->
                adapter.submit(list)
                showLatest(list.firstOrNull())
            }
        }
    }

    override fun onStart() {
        super.onStart()
        settings = app.settingsStore.settings()
        b.title.text = settings.siteTitle
        val cameras = app.settingsStore.cameras().filter { it.enabled && it.url.isNotBlank() }
        buildTiles(cameras)
        b.emptyHint.text = if (cameras.isEmpty()) getString(R.string.no_cameras) else ""
        if (cameras.isNotEmpty()) {
            pipeline.start(cameras.map { it to tiles.getValue(it.id).video }, settings)
        }
        if (settings.beepOnEntry && tone == null) {
            tone = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) } catch (e: Exception) { null }
        }
        handler.post(clockTick)
        if (cameras.isEmpty()) b.settingsButton.requestFocus()
    }

    override fun onStop() {
        handler.removeCallbacks(clockTick)
        pipeline.stop()
        tone?.release()
        tone = null
        super.onStop()
    }

    // TV remotes: MENU / SETTINGS key opens settings from anywhere.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            openSettings(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    // ------------------------------------------------------------------ tiles

    private fun buildTiles(cameras: List<CameraConfig>) {
        b.cameraStrip.removeAllViews()
        tiles.clear()
        b.cameraStrip.visibility = if (cameras.isEmpty()) View.GONE else View.VISIBLE
        val compact = !settings.showLiveVideo
        b.cameraStrip.layoutParams = b.cameraStrip.layoutParams.apply {
            height = ((if (compact) 46 else 118) * resources.displayMetrics.density).toInt()
        }
        val inflater = LayoutInflater.from(this)
        cameras.forEachIndexed { i, cam ->
            val v = inflater.inflate(R.layout.item_camera_tile, b.cameraStrip, false)
            if (i > 0) (v.layoutParams as android.widget.LinearLayout.LayoutParams).marginStart =
                (8 * resources.displayMetrics.density).toInt()
            val tile = Tile(v, v.findViewById(R.id.video), v.findViewById(R.id.statusDot),
                v.findViewById(R.id.statusText), v.findViewById(R.id.lastRead),
                v.findViewById(R.id.name), cam.name, compact)
            tile.refreshName()
            // The video view renders at ITS OWN size. A small tile would mean scanning a tiny
            // picture, so render at full HD off-screen and only scale it down for display.
            tile.video.layoutParams = android.widget.FrameLayout.LayoutParams(RENDER_W, RENDER_H)
            tile.video.pivotX = 0f
            tile.video.pivotY = 0f
            v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val w = (view.width - view.paddingLeft - view.paddingRight).coerceAtLeast(1)
                val h = (view.height - view.paddingTop - view.paddingBottom).coerceAtLeast(1)
                tile.video.scaleX = w.toFloat() / RENDER_W
                tile.video.scaleY = h.toFloat() / RENDER_H
            }
            // Tap a camera to open its preview (check picture, search area, Read plate now).
            v.isClickable = true
            v.setOnClickListener { PreviewActivity.start(this, cam) }
            v.findViewById<RoiOverlayView>(R.id.roiOverlay).apply {
                showLabel = false
                setRoi(cam.roiLeft, cam.roiTop, cam.roiRight, cam.roiBottom)
                visibility = if (compact) View.GONE else View.VISIBLE
            }
            if (compact) {
                v.findViewById<View>(R.id.cover).visibility = View.VISIBLE
                tile.status.visibility = View.GONE
            }
            setTileStatus(tile, CameraStream.Status.CONNECTING, null)
            tiles[cam.id] = tile
            b.cameraStrip.addView(v)
        }
    }

    private fun setTileStatus(t: Tile, s: CameraStream.Status, msg: String?) {
        val color = when (s) {
            CameraStream.Status.LIVE -> R.color.live
            CameraStream.Status.CONNECTING -> R.color.warn
            CameraStream.Status.RECONNECTING -> R.color.error
            CameraStream.Status.STOPPED -> R.color.text_dim
        }
        t.dot.setColorFilter(ContextCompat.getColor(this, color))
        t.statusLabel = when (s) {
            CameraStream.Status.LIVE -> "Live"
            CameraStream.Status.CONNECTING -> "Connecting…"
            CameraStream.Status.RECONNECTING -> "Offline"
            CameraStream.Status.STOPPED -> "Stopped"
        }
        t.refreshName()
        if (t.compact) return
        t.status.text = when (s) {
            CameraStream.Status.LIVE -> ""
            CameraStream.Status.CONNECTING -> "Connecting…"
            CameraStream.Status.RECONNECTING -> "Offline\n${msg ?: ""}"
            CameraStream.Status.STOPPED -> "Stopped"
        }
    }

    override fun onCameraStatus(cameraId: String, status: CameraStream.Status, message: String?) {
        val run = Runnable { tiles[cameraId]?.let { setTileStatus(it, status, message) } }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else handler.post(run)
    }

    override fun onCameraScan(cameraId: String, scans: Int, motion: Boolean) {
        val t = tiles[cameraId] ?: return
        t.scans = scans
        t.motion = motion
        t.refreshName()
    }

    override fun onSkipped(plate: String, reason: String) {
        b.lastEvent.text = "Last skipped: $plate – $reason  (${timeFmt.format(Date())})"
    }

    override fun onRawRead(cameraId: String, plate: String, scorePct: Int) {
        val t = tiles[cameraId] ?: return
        t.reads++
        t.refreshName()
        t.lastRead.text = "$plate  $scorePct%"
        t.lastRead.visibility = View.VISIBLE
        t.lastRead.removeCallbacks(t.hideRead)
        t.lastRead.postDelayed(t.hideRead, 4000)
    }

    // ------------------------------------------------------------------ latest vehicle

    override fun onVehicleSaved(entry: VehicleEntry) {
        if (settings.beepOnEntry) tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
        // The Room flow will refresh the list; flash the plate to draw attention.
        b.plateBox.animate().cancel()
        b.plateBox.scaleX = 1.12f; b.plateBox.scaleY = 1.12f
        b.plateBox.animate().scaleX(1f).scaleY(1f).setDuration(600).start()
    }

    private fun showLatest(e: VehicleEntry?) {
        if (e == null) {
            shownLatestId = -1
            b.latestPlate.text = "— — —"
            b.latestDate.text = getString(R.string.waiting)
            b.latestTime.text = ""
            b.latestConfidence.text = ""
            b.latestCameras.text = ""
            b.latestSnapshot.setImageDrawable(null)
            return
        }
        if (e.id == shownLatestId) return
        shownLatestId = e.id
        b.latestPlate.text = e.plateFormatted
        b.latestDate.text = dateFmt.format(Date(e.entryTime))
        b.latestTime.text = timeFmt.format(Date(e.entryTime))
        b.latestConfidence.text = "Confidence ${(e.confidence * 100).toInt()}%  •  " +
            "${e.totalReads} reads from ${e.cameraCount} camera${if (e.cameraCount == 1) "" else "s"}"
        b.latestCameras.text = formatCameraSummary(e.cameraSummary)
        b.emptyHint.text = ""

        val path = e.snapshotPath
        if (path == null) {
            b.latestSnapshot.setImageDrawable(null)
        } else lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
            if (shownLatestId == e.id) b.latestSnapshot.setImageBitmap(bmp)
        }
    }

    /** "Gate Cam 1|TN 09 AB 1234|92|3|1" lines → aligned comparison text. */
    private fun formatCameraSummary(summary: String): String =
        summary.lines().filter { it.isNotBlank() }.joinToString("\n") { line ->
            val p = line.split("|")
            if (p.size < 5) line
            else {
                val mark = if (p[4] == "1") "✔" else "✘"
                "$mark ${p[0].take(14).padEnd(14)}  ${p[1].padEnd(14)}  ${p[2].padStart(3)}%  ×${p[3]}"
            }
        }

    // ------------------------------------------------------------------ clock & today count

    private val clockTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            b.clock.text = clockFmt.format(Date(now))
            val dayStart = startOfDay(now)
            if (dayStart != countDayStart) {
                countDayStart = dayStart
                countJob?.cancel()
                countJob = lifecycleScope.launch {
                    app.db.entries().countSince(dayStart).collect { b.todayCount.text = "Today: $it" }
                }
            }
            handler.postDelayed(this, 1000 - now % 1000)
        }
    }

    private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
