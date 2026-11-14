package com.vehiclegate.anpr.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vehiclegate.anpr.AnprApp
import com.vehiclegate.anpr.AnprPipeline
import com.vehiclegate.anpr.ParkingDesk
import com.vehiclegate.anpr.R
import com.vehiclegate.anpr.camera.CameraStream
import com.vehiclegate.anpr.data.Role
import com.vehiclegate.anpr.data.VehicleEntry
import com.vehiclegate.anpr.databinding.ActivityDashboardBinding
import com.vehiclegate.anpr.service.AnprEngine
import com.vehiclegate.anpr.service.AnprService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * TV dashboard (view only): parked now, in / out / collected today, latest check-ins as number
 * plates, vehicles waiting at the counter and camera status. The TV role opens it directly;
 * leaving it needs an admin PIN.
 */
class DashboardActivity : AppCompatActivity(), AnprPipeline.Events {

    private lateinit var b: ActivityDashboardBinding
    private val app get() = application as AnprApp
    private val handler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("EEE, dd MMM  ·  hh:mm a", Locale.getDefault())
    private val camStatus = LinkedHashMap<String, Pair<String, CameraStream.Status>>()
    private var dayStart = 0L
    private var dayJob: Job? = null
    private var latest: List<VehicleEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (app.session.user == null) {
            startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish(); return
        }
        b = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.title.text = app.settingsStore.settings().siteTitle
        b.leave.setOnClickListener { leave() }

        b.latest.layoutManager = GridLayoutManager(this, 3)
        b.latest.adapter = adapter
        b.latest.itemAnimator = null

        lifecycleScope.launch {
            app.db.entries().openFlow().collect { list ->
                latest = list.take(12)
                b.parkedNow.text = list.size.toString()
                adapter.notifyDataSetChanged()
            }
        }
        lifecycleScope.launch {
            app.db.captures().pendingFlow().collect { list ->
                b.waiting.text = list.size.toString()
                b.waitingList.text = list.take(6).joinToString("\n") { "${it.plateFormatted}   ${shortTime(it.firstSeen)}" }
            }
        }
        for (c in app.settingsStore.cameras().filter { it.enabled }) camStatus[c.id] = c.name to CameraStream.Status.CONNECTING
        showCameras()
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() {
        super.onStart()
        // this device may run cameras too: keep scanning in the background
        if (app.settingsStore.cameras().any { it.enabled }) AnprService.start(this)
        AnprEngine.addListener(this)
        handler.post(tick)
    }

    override fun onStop() {
        AnprEngine.removeListener(this)
        handler.removeCallbacks(tick)
        super.onStop()
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            b.clock.text = clockFmt.format(Date(now))
            val start = Calendar.getInstance().apply {
                timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            if (start != dayStart) {
                dayStart = start
                dayJob?.cancel()
                dayJob = lifecycleScope.launch {
                    val dao = app.db.entries()
                    launch { dao.countSince(start).collect { b.inToday.text = it.toString() } }
                    combine(dao.outCountSince(start), dao.collectedSince(start)) { n, amt -> n to amt }
                        .collect { (n, amt) -> b.outToday.text = n.toString(); b.collected.text = "₹$amt" }
                }
            }
            adapter.notifyDataSetChanged() // parked durations
            handler.postDelayed(this, 30_000 - now % 30_000)
        }
    }

    private fun showCameras() {
        b.cameras.text = if (camStatus.isEmpty()) "No cameras on this device"
        else camStatus.values.joinToString("\n") { (name, st) ->
            (if (st == CameraStream.Status.LIVE) "●  " else "○  ") + name + "  –  " +
                (if (st == CameraStream.Status.LIVE) "live" else st.name.lowercase())
        }
    }

    // ------------------------------------------------------------------ leaving

    private fun leave() {
        if (app.session.user?.role == Role.TV) {
            withAdmin("Leaving the TV dashboard needs an admin PIN.") {
                app.session.logout()
                startActivity(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_SWITCH, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
        } else finish()
    }

    @Deprecated("Back key")
    override fun onBackPressed() = leave()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) { leave(); return true }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ engine events

    override fun onCameraStatus(cameraId: String, status: CameraStream.Status, message: String?) {
        handler.post {
            val name = camStatus[cameraId]?.first ?: cameraId
            camStatus[cameraId] = name to status
            showCameras()
        }
    }
    override fun onRawRead(cameraId: String, plate: String, scorePct: Int) {}
    override fun onCameraPicture(cameraId: String, picture: Bitmap) {}
    override fun onVehicleSaved(entry: VehicleEntry) {}

    // ------------------------------------------------------------------ latest check-ins

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = latest.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            object : RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_dash_plate, p, false)) {}
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val e = latest[i]
            val v = h.itemView
            val manual = e.cameraSummary == ParkingDesk.MANUAL && !e.isMonthly
            v.findViewById<View>(R.id.plateBox).setBackgroundResource(when {
                e.isMonthly -> R.drawable.plate_small_monthly_bg
                manual -> R.drawable.plate_small_manual_bg
                else -> R.drawable.plate_small_bg
            })
            v.findViewById<TextView>(R.id.plate).text = e.plateFormatted
            v.findViewById<TextView>(R.id.who).apply {
                text = listOfNotNull(
                    if (e.isMonthly) "MONTHLY" else if (manual) "MANUAL" else null,
                    e.name?.takeIf { it.isNotBlank() }
                ).joinToString(" · ").ifEmpty { "–" }
                setTextColor(ContextCompat.getColor(context, if (e.isMonthly) R.color.monthly_text else if (manual) R.color.manual else R.color.text))
            }
            v.findViewById<TextView>(R.id.time).text =
                "In ${shortTime(e.entryTime)} · ${com.vehiclegate.anpr.core.ParkingFee.duration(e.entryTime, System.currentTimeMillis())}"
        }
    }
}
