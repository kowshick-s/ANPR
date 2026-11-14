package com.vehiclegate.anpr.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.vehiclegate.anpr.AnprApp
import com.vehiclegate.anpr.AnprPipeline
import com.vehiclegate.anpr.ParkingDesk
import com.vehiclegate.anpr.R
import com.vehiclegate.anpr.camera.CameraStream
import com.vehiclegate.anpr.data.AppSettings
import com.vehiclegate.anpr.data.CameraConfig
import com.vehiclegate.anpr.data.Capture
import com.vehiclegate.anpr.data.VehicleEntry
import com.vehiclegate.anpr.databinding.ActivityMainBinding
import com.vehiclegate.anpr.service.AnprEngine
import com.vehiclegate.anpr.service.AnprService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Counter screen: CAPTURED waiting list (camera reads waiting for staff), PARKED vehicles with search
 * (check out here) and recent CHECKED OUT, plus live camera tiles.
 */
class MainActivity : AppCompatActivity(), AnprPipeline.Events, CaptureAdapter.Host {

    private lateinit var b: ActivityMainBinding
    private val app get() = application as AnprApp

    private val captureAdapter = CaptureAdapter(this)
    private val parkedAdapter = CheckInAdapter(
        onSave = { e, who, m ->
            lifecycleScope.launch {
                val dup = withContext(Dispatchers.IO) { app.parking.saveDetails(e, who, m) }
                if (dup != null) b.lastEvent.text = "Token $who is also given to ${dup.plateFormatted} – check the token"
            }
            if (m.isNotBlank()) addModelSuggestion(m)
        },
        modelSuggestions = { modelAdapter },
        onPlateEdit = { e, p -> correctPlate(e, p) },
        amountFor = { app.parking.amountDue(it, settings) },
        onCheckOut = { checkOut(it) },
        onPhoto = { showPicture(it.snapshotPath, "${it.plateFormatted}  ·  In ${shortTime(it.entryTime)}  ·  ${it.whoText()}") },
        onLongClick = { confirmDelete(it) }
    )
    private val outAdapter = OutAdapter(
        onUndo = { e ->
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { app.parking.undoCheckOut(e) }
                b.lastEvent.text = "Check-out undone: ${e.plateFormatted} is parked again"
            }
        },
        onPhoto = { showPicture(it.snapshotPath, "${it.plateFormatted}  ·  ${it.whoText()}") }
    )

    /** Model suggestions while typing: common models + every model typed before. */
    private var modelAdapter: android.widget.ArrayAdapter<String>? = null
    private val modelNames = java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER)

    private fun newModelAdapter() =
        android.widget.ArrayAdapter(this, R.layout.item_suggestion, modelNames.toMutableList())

    private fun addModelSuggestion(m: String) {
        if (modelNames.add(m.trim())) modelAdapter?.add(m.trim())
    }
    private val handler = Handler(Looper.getMainLooper())

    private class Tile(val root: View, val picture: ImageView, val dot: ImageView,
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

    // counter state
    private var pending: List<Capture> = emptyList()
    private var parkedAll: List<VehicleEntry> = emptyList()
    private var outAll: List<VehicleEntry> = emptyList()
    private var manualOpen = false
    private var showOut = false
    private var lookupJob: Job? = null

    private val clockFmt = SimpleDateFormat("EEE, dd MMM yyyy  ·  hh:mm:ss a", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        settings = app.settingsStore.settings()

        for ((list, adapter) in listOf(b.captureList to captureAdapter, b.parkedList to parkedAdapter, b.outList to outAdapter)) {
            list.layoutManager = LinearLayoutManager(this)
            list.adapter = adapter
            list.itemAnimator = null
        }
        modelNames.addAll(com.vehiclegate.anpr.core.VehicleModels.COMMON)
        modelAdapter = newModelAdapter()
        lifecycleScope.launch {
            val known = withContext(Dispatchers.IO) { app.db.entries().knownModels() }
            known.forEach { addModelSuggestion(it) }
        }
        b.settingsButton.setOnClickListener { openSettings() }
        b.exitButton.setOnClickListener { confirmExit() }
        b.manualButton.setOnClickListener { openManual() }
        b.tabParked.setOnClickListener { showOut = false; refreshLists() }
        b.tabOut.setOnClickListener { showOut = true; refreshLists() }
        b.search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refreshLists()
        })
        askPermissionsOnce()

        lifecycleScope.launch {
            app.db.captures().pendingFlow().collect { list -> pending = list; refreshCaptures() }
        }
        lifecycleScope.launch {
            app.db.entries().openFlow().collect { list ->
                parkedAll = list
                refreshLists()
                captureAdapter.notifyDataSetChanged() // "Parked · leaving?" badges
            }
        }
        lifecycleScope.launch {
            app.db.entries().recentOut(500).collect { list -> outAll = list; refreshLists() }
        }
        refreshLists()
    }

    // ------------------------------------------------------------------ counter lists

    private fun refreshCaptures() {
        val list = if (manualOpen) listOf(manualCapture()) + pending else pending
        captureAdapter.submitList(list)
        b.captureCount.text = "${pending.size}"
        b.captureEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        // the opened card was checked in / ignored / expired
        val open = captureAdapter.expandedId
        if (open != null && list.none { it.id == open }) captureAdapter.expand(null)
    }

    private fun manualCapture() = Capture(
        id = CaptureAdapter.MANUAL_ID, plate = "", plateFormatted = "", firstSeen = 0, lastSeen = 0,
        reads = 0, readings = "", snapshotPath = null, confidence = 1f, cameraSummary = ParkingDesk.MANUAL
    )

    private fun openManual() {
        manualOpen = true
        refreshCaptures()
        b.captureList.scrollToPosition(0)
    }

    /** Parked / Checked out list with search. Digits typed = token first. */
    private fun refreshLists() {
        b.tabParked.isSelected = !showOut
        b.tabOut.isSelected = showOut
        b.tabParked.text = "Parked · ${parkedAll.size}"
        b.parkedList.visibility = if (showOut) View.GONE else View.VISIBLE
        b.outList.visibility = if (showOut) View.VISIBLE else View.GONE
        val q = b.search.text.toString().trim()
        val source = if (showOut) outAll else parkedAll
        val shown = if (q.isEmpty()) source.take(LIST_LIMIT)
        else source.mapNotNull { e ->
            com.vehiclegate.anpr.core.CaptureLogic.rank(q, e.plate, e.name?.ifBlank { e.token } ?: e.token, e.model)?.let { it to e }
        }.sortedWith(compareBy<Pair<Int, VehicleEntry>> { it.first }.thenByDescending { it.second.exitTime ?: it.second.entryTime })
            .map { it.second }
        if (showOut) outAdapter.submitList(shown) else parkedAdapter.submitList(shown)
        b.listNote.text = when {
            q.isNotEmpty() -> "${shown.size} found for \"$q\"" + if (q.all { it.isDigit() }) " · token numbers first" else ""
            showOut -> "Latest ${shown.size} check-outs · search to find older ones"
            source.size > shown.size -> "Latest ${shown.size} of ${source.size} parked · search token, number or name"
            else -> "${source.size} parked · type in a row to edit · Check out = tap twice"
        }
        b.listEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        b.listEmpty.text = when {
            q.isNotEmpty() -> "Nothing found for \"$q\""
            showOut -> "No check-outs yet."
            else -> "No vehicles parked."
        }
    }

    // ------------------------------------------------------------------ CaptureAdapter.Host

    override fun parkedFor(c: Capture): VehicleEntry? = app.parking.parkedMatch(c, parkedAll)

    override fun amountFor(e: VehicleEntry) = app.parking.amountDue(e, settings)

    override fun modelSuggestions() = modelAdapter

    override fun lookup(c: Capture, typed: String, show: (String, ParkingDesk.Hint) -> Unit) {
        lookupJob?.cancel()
        lookupJob = lifecycleScope.launch {
            val hint = withContext(Dispatchers.IO) { app.parking.hint(typed, settings) }
            if (hint is ParkingDesk.Hint.Monthly && hint.exact && hint.member.plate !in parkedAll.map { it.plate }) {
                // Monthly user typed / picked → checked in straight away
                checkIn(c, typed, "", "", auto = true)
                return@launch
            }
            val text = when (hint) {
                is ParkingDesk.Hint.Monthly -> "Monthly user? ${hint.member.name} (${hint.member.plate}) – Check in uses that number"
                is ParkingDesk.Hint.Parked -> "Already parked since ${shortTime(hint.entry.entryTime)} · ${hint.entry.whoText()}" +
                    if (hint.entry.isMonthly) " · monthly" else " · collect ₹${hint.amount}"
                is ParkingDesk.Hint.Regular -> "Regular: " + listOf(hint.name, hint.model).filter { it.isNotBlank() }.joinToString(" · ")
                ParkingDesk.Hint.None -> if (typed.filter { it.isLetterOrDigit() }.length >= 4) "New vehicle" else ""
            }
            show(text, hint)
        }
    }

    override fun checkIn(c: Capture, plate: String, who: String, model: String) = checkIn(c, plate, who, model, auto = false)

    private fun checkIn(c: Capture, plate: String, who: String, model: String, auto: Boolean) {
        val manual = c.id == CaptureAdapter.MANUAL_ID
        lifecycleScope.launch {
            val dup = withContext(Dispatchers.IO) { app.parking.duplicateToken(who) }
            val (entry, err) = withContext(Dispatchers.IO) {
                app.parking.checkIn(if (manual) null else c, plate, who, model)
            }
            if (entry == null) { b.lastEvent.text = err ?: "Not checked in"; return@launch }
            if (model.isNotBlank()) addModelSuggestion(model)
            hideKeyboard()
            b.root.isFocusableInTouchMode = true
            b.root.requestFocus()
            if (manual) { manualOpen = false; refreshCaptures() }
            captureAdapter.expand(null)
            b.lastEvent.text = when {
                entry.isMonthly -> "Monthly user checked in${if (auto) " automatically" else ""}: ${entry.plateFormatted} · ${entry.whoText()}"
                dup != null -> "Checked in ${entry.plateFormatted} – but token $who is also given to ${dup.plateFormatted}"
                else -> "Checked in: ${entry.plateFormatted} · ${entry.whoText()}"
            }
            b.parkedList.scrollToPosition(0)
        }
    }

    override fun checkOut(c: Capture, parked: VehicleEntry) {
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) { app.parking.checkOutFromCapture(c, parked, settings) }
            hideKeyboard()
            captureAdapter.expand(null)
            b.lastEvent.text = "Checked out: ${out.plateFormatted}" + if (out.isMonthly) " (monthly)" else " · collected ₹${out.amount ?: 0}"
        }
    }

    override fun ignore(c: Capture) {
        if (c.id == CaptureAdapter.MANUAL_ID) {
            manualOpen = false; hideKeyboard(); refreshCaptures(); return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { app.parking.ignore(c) }
            hideKeyboard()
            captureAdapter.expand(null)
        }
    }

    override fun photo(c: Capture) = showPicture(c.snapshotPath, "${c.plateFormatted}  ·  ${shortTime(c.firstSeen)}  ·  ${c.reads} reads")

    // small pictures for the capture cards
    private val thumbCache = android.util.LruCache<String, android.graphics.Bitmap>(40)
    private val thumbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun loadThumb(path: String?, into: ImageView) {
        into.tag = path
        if (path == null) { into.setImageResource(R.drawable.ic_photo); into.scaleType = ImageView.ScaleType.CENTER; return }
        into.scaleType = ImageView.ScaleType.CENTER_CROP
        thumbCache.get(path)?.let { into.setImageBitmap(it); return }
        into.setImageDrawable(null)
        thumbExecutor.execute {
            val bmp = try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var sample = 1
                while (o.outWidth / (sample * 2) >= 320) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (_: Exception) { null }
            handler.post {
                if (bmp != null) thumbCache.put(path, bmp)
                if (into.tag == path) { if (bmp != null) into.setImageBitmap(bmp) else into.setImageResource(R.drawable.ic_photo) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        settings = app.settingsStore.settings()
        b.title.text = settings.siteTitle
        refreshLists()
        val cameras = app.settingsStore.cameras().filter { it.enabled && it.url.isNotBlank() }
        buildTiles(cameras)
        if (cameras.isEmpty()) b.lastEvent.text = getString(R.string.no_cameras).replace("\n", " ")
        // Detection runs in the background service; (re)start it – it applies changed settings –
        // and attach this screen to it.
        AnprService.start(this)
        AnprEngine.addListener(this)
        restoreFromEngine()
        handler.post(clockTick)
        if (cameras.isEmpty()) b.settingsButton.requestFocus()
    }

    override fun onStop() {
        // Only detach the screen – scanning continues in the background.
        AnprEngine.removeListener(this)
        handler.removeCallbacks(clockTick)
        super.onStop()
    }

    /** Show the state the background scanner already has (scans, reads, pictures, status). */
    private fun restoreFromEngine() {
        for ((id, st) in AnprEngine.camStates) {
            val t = tiles[id] ?: continue
            setTileStatus(t, st.status, st.message)
            t.scans = st.scans; t.motion = st.motion; t.reads = st.reads
            t.refreshName()
            st.picture?.let { if (!t.compact) t.picture.setImageBitmap(it) }
        }
        AnprEngine.lastSkipped?.let { b.lastEvent.text = "Last skipped: $it" }
    }

    private fun confirmExit() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Exit and stop scanning?")
            .setMessage("Vehicle detection stops until you open the app again. " +
                "To keep detecting in the background, just press Home instead.")
            .setPositiveButton("Exit") { _, _ ->
                AnprService.stop(this)
                finishAndRemoveTask()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Notifications (Android 13+) and "don't optimise battery" so Android doesn't kill scanning. */
    private fun askPermissionsOnce() {
        val prefs = getSharedPreferences("ui_once", MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("askedNotif", false)
        ) {
            prefs.edit().putBoolean("askedNotif", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        if (android.os.Build.VERSION.SDK_INT >= 23 && !prefs.getBoolean("askedBattery", false)) {
            prefs.edit().putBoolean("askedBattery", true).apply()
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:$packageName")))
                } catch (_: Exception) { /* not available on some TV boxes */ }
            }
        }
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
            val tile = Tile(v, v.findViewById(R.id.picture), v.findViewById(R.id.statusDot),
                v.findViewById(R.id.statusText), v.findViewById(R.id.lastRead),
                v.findViewById(R.id.name), cam.name, compact)
            tile.refreshName()
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

    override fun onCameraPicture(cameraId: String, picture: android.graphics.Bitmap) {
        val t = tiles[cameraId] ?: return
        if (!t.compact) t.picture.setImageBitmap(picture)
    }

    override fun onSkipped(plate: String, reason: String) {
        b.lastEvent.text = "Last skipped: $plate – $reason  (${timeFmt.format(Date())})"
    }

    override fun onRawRead(cameraId: String, plate: String, scorePct: Int) {
        val t = tiles[cameraId] ?: return
        t.reads = AnprEngine.camStates[cameraId]?.reads ?: (t.reads + 1)
        t.refreshName()
        t.lastRead.text = "$plate  $scorePct%"
        t.lastRead.visibility = View.VISIBLE
        t.lastRead.removeCallbacks(t.hideRead)
        t.lastRead.postDelayed(t.hideRead, 4000)
    }

    // ------------------------------------------------------------------ pipeline events

    override fun onCaptured(capture: Capture, isNew: Boolean) {
        if (isNew) b.lastEvent.text = "Captured ${capture.plateFormatted} at ${shortTime(capture.firstSeen)} – select it to check in"
    }

    override fun onAutoCheckedOut(entry: VehicleEntry) {
        b.lastEvent.text = "Monthly user checked out automatically: ${entry.plateFormatted} (${timeFmt.format(Date(entry.exitTime ?: 0))})"
    }

    override fun onVehicleSaved(entry: VehicleEntry) {
        b.lastEvent.text = "Monthly user checked in automatically: ${entry.plateFormatted} · ${entry.whoText()}"
    }

    // ------------------------------------------------------------------ parking actions

    /** Tap outside the box being typed in: save it and close the keyboard. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            val f = currentFocus
            if (f is android.widget.EditText) {
                val r = android.graphics.Rect(); f.getGlobalVisibleRect(r)
                if (!r.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    val touchedBox = findEditTextAt(b.root, ev.rawX.toInt(), ev.rawY.toInt())
                    if (touchedBox == null) {
                        // root takes the focus → the box saves (focus lost) and the keyboard closes
                        b.root.isFocusableInTouchMode = true
                        b.root.requestFocus()
                        hideKeyboard()
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun findEditTextAt(v: View, x: Int, y: Int): View? {
        if (v.visibility != View.VISIBLE) return null
        val r = android.graphics.Rect()
        if (!v.getGlobalVisibleRect(r) || !r.contains(x, y)) return null
        if (v is android.widget.EditText) return v
        if (v is android.view.ViewGroup) for (i in v.childCount - 1 downTo 0) findEditTextAt(v.getChildAt(i), x, y)?.let { return it }
        return null
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(b.root.windowToken, 0)
    }

    private fun checkOut(e: VehicleEntry) {
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) { app.parking.checkOut(app.db.entries().get(e.id) ?: e, settings) }
            b.lastEvent.text = "Checked out: ${out.plateFormatted}" + if (out.isMonthly) " (monthly)" else " · collected ₹${out.amount ?: 0}"
        }
    }

    /** Captured picture of the vehicle (plate boxed), large. Tap anywhere to close. */
    private fun showPicture(path: String?, title: String) {
        if (path == null) { b.lastEvent.text = "No picture saved"; return }
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
            if (bmp == null) { b.lastEvent.text = "The picture is no longer stored"; return@launch }
            val dlg = android.app.Dialog(this@MainActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val box = android.widget.LinearLayout(this@MainActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(0xFF000000.toInt())
                setPadding(16, 16, 16, 16)
            }
            box.addView(TextView(this@MainActivity).apply {
                text = "$title      (tap to close)"
                setTextColor(0xFFFFFFFF.toInt()); textSize = 18f; setPadding(8, 4, 8, 12)
            })
            box.addView(ImageView(this@MainActivity).apply {
                setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, 0, 1f)
            })
            box.setOnClickListener { dlg.dismiss() }
            box.isFocusable = true
            dlg.setContentView(box)
            dlg.show()
        }
    }

    /** Plate corrected in a row. */
    private fun correctPlate(e: VehicleEntry, typed: String) {
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) { app.parking.correctPlate(e, typed) }
            b.lastEvent.text = if (err == null) "Number corrected: ${e.plateFormatted} → ${typed.uppercase()} (remembered for next time)"
                else "Number not changed: $err"
        }
    }

    /** Long-press a parked vehicle: remove a wrong entry. */
    private fun confirmDelete(e: VehicleEntry) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${e.plateFormatted}?")
            .setMessage("Use this only for a wrong entry (no such vehicle). It is removed from the records.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch { withContext(Dispatchers.IO) { app.db.entries().delete(e) } }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                    launch { app.db.entries().countSince(dayStart).collect { b.todayCount.text = "In today: $it" } }
                    val dao = app.db.entries()
                    kotlinx.coroutines.flow.combine(dao.outCountSince(dayStart), dao.collectedSince(dayStart)) { n, amt -> n to amt }
                        .collect { (n, amt) -> b.outCount.text = "Out: $n · ₹$amt" }
                }
            }
            handler.postDelayed(this, 1000 - now % 1000)
        }
    }

    companion object {
        /** Parked / checked-out rows shown without a search */
        private const val LIST_LIMIT = 15
    }

    private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
