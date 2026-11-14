package com.vehiclegate.anpr.ui

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.view.View
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vehiclegate.anpr.AnprApp
import com.vehiclegate.anpr.data.AppSettings
import com.vehiclegate.anpr.data.CameraConfig
import com.vehiclegate.anpr.databinding.ActivitySettingsBinding
import com.vehiclegate.anpr.databinding.DialogCameraBinding
import com.vehiclegate.anpr.databinding.DialogNvrBinding
import com.vehiclegate.anpr.databinding.ItemCameraConfigBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private val app get() = application as AnprApp
    private val cameras = ArrayList<CameraConfig>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!app.session.isAdmin && app.session.user != null && !intent.getBooleanExtra(EXTRA_ADMIN_OK, false)) { finish(); return }
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        loadSettings(app.settingsStore.settings())

        b.addCamera.setOnClickListener { editCamera(null) }
        b.addNvr.setOnClickListener { addNvrChannels() }
        b.addTestVideo.setOnClickListener {
            try {
                pickVideo.launch(arrayOf("video/*"))
            } catch (e: android.content.ActivityNotFoundException) {
                toast("This device has no file picker – add the test video on a phone/tablet.")
            }
        }
        b.save.setOnClickListener { save(); finish() }
        b.clearHistory.setOnClickListener { confirmClear() }
        b.exportCsv.setOnClickListener { exportCsv() }
        b.exportSettings.setOnClickListener { backupSettings() }
        b.usersButton.setOnClickListener {
            startActivity(android.content.Intent(this, UsersActivity::class.java)
                .putExtra(EXTRA_ADMIN_OK, app.session.isAdmin || intent.getBooleanExtra(EXTRA_ADMIN_OK, false)))
        }
        b.protectAdmin.setOnClickListener {
            val P = com.vehiclegate.anpr.admin.DeviceProtection
            if (P.isAdminActive(this) && !P.isDeviceOwner(this)) { P.removeAdmin(this); showProtection() } else P.askAdmin(this)
        }
        b.protectKiosk.setOnCheckedChangeListener { _, on ->
            val P = com.vehiclegate.anpr.admin.DeviceProtection
            if (on != P.isKiosk(this)) {
                P.setKiosk(this, on)
                if (on) P.enterLockTask(this) else P.leaveLockTask(this)
                showProtection()
            }
        }
        b.protectRelease.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Release this device?")
                .setMessage("Kiosk mode and the uninstall block are removed. Do this only for maintenance or to uninstall.")
                .setPositiveButton("Release") { _, _ ->
                    com.vehiclegate.anpr.admin.DeviceProtection.leaveLockTask(this)
                    com.vehiclegate.anpr.admin.DeviceProtection.releaseDeviceOwner(this)
                    showProtection()
                }
                .setNegativeButton("Cancel", null).show()
        }
        showProtection()
        setupSections()

        // emergency backup by email
        val backup = com.vehiclegate.anpr.backup.BackupStore(this)
        b.backupEnabled.isChecked = backup.enabled
        b.backupGmail.setText(backup.gmail)
        b.backupAlsoTo.setText(backup.alsoTo)
        if (backup.appPassword.isNotEmpty()) b.backupPassword.hint = "saved – type to change"
        b.backupStatus.text = backup.lastResult
        fun saveBackup() {
            backup.enabled = b.backupEnabled.isChecked
            backup.gmail = b.backupGmail.text.toString()
            backup.alsoTo = b.backupAlsoTo.text.toString()
            b.backupPassword.text.toString().takeIf { it.isNotBlank() }?.let { backup.appPassword = it }
        }
        b.backupEnabled.setOnCheckedChangeListener { _, _ -> saveBackup() }
        b.backupNow.setOnClickListener {
            saveBackup()
            b.backupStatus.text = "Sending…"
            lifecycleScope.launch {
                val err = withContext(Dispatchers.IO) { com.vehiclegate.anpr.backup.BackupWorker.sendBackup(app, force = true) }
                b.backupStatus.text = if (err == null) backup.lastResult else "Not sent: $err"
            }
        }
        b.monthlyUsers.setOnClickListener { startActivity(android.content.Intent(this, MonthlyUsersActivity::class.java)) }
        b.importSettings.setOnClickListener {
            try {
                pickBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
            } catch (e: android.content.ActivityNotFoundException) {
                toast("This device has no file picker – restore on a phone/tablet, or re-add cameras with '+ Add NVR channels'.")
            }
        }
        b.addCamera.requestFocus()
    }

    /**
     * Test video: copies the chosen clip into the app and adds it as a camera that plays on a loop,
     * so detection can be tested anywhere without the real cameras.
     */
    private val pickVideo = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        toast("Copying video into the app…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    var name = "test_video.mp4"
                    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) name = c.getString(0) ?: name
                    }
                    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val dir = File(filesDir, "test_videos").apply { mkdirs() }
                    val out = File(dir, "${System.currentTimeMillis()}_$safe")
                    contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                        ?: return@withContext null
                    name to out
                } catch (e: Exception) { null }
            }
            if (result == null) { toast("Could not read that video"); return@launch }
            val (name, file) = result
            cameras += CameraConfig(
                name = "Test video – ${name.substringBeforeLast('.').take(24)}",
                url = android.net.Uri.fromFile(file).toString(),
                useTcp = false
            )
            persistCameras()
            toast("Test video added (${file.length() / (1024 * 1024)} MB). Press ▶ Preview on it, or go back to the dashboard.")
        }
    }

    /** Restore: user picks the backup file (e.g. Downloads/vehicle_gate_settings.json). */
    private val pickBackup = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: return@withContext "Could not read the file"
                    val n = app.settingsStore.importJson(text)
                    var members = 0
                    org.json.JSONObject(text).optJSONArray("monthlyUsers")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            app.db.members().upsert(com.vehiclegate.anpr.data.MonthlyMember(
                                o.getString("plate"), o.optString("name"), o.optString("phone"),
                                if (o.isNull("validTill")) null else o.optLong("validTill"), o.optString("notes")))
                            members++
                        }
                    }
                    "Restored $n camera(s), $members monthly user(s) and all settings"
                } catch (e: Exception) {
                    "Restore failed: ${e.message}"
                }
            }
            cameras.clear(); cameras += app.settingsStore.cameras(); renderCameras()
            loadSettings(app.settingsStore.settings())
            toast(msg)
        }
    }

    /** Backup: writes cameras (with RTSP URLs) + settings to Downloads/vehicle_gate_settings.json. */
    private fun backupSettings() {
        save() // include anything typed on this screen
        lifecycleScope.launch {
            val where = withContext(Dispatchers.IO) {
                // settings + cameras + monthly users master list
                val json = org.json.JSONObject(app.settingsStore.exportJson()).apply {
                    val arr = org.json.JSONArray()
                    app.db.members().all().forEach { m ->
                        arr.put(org.json.JSONObject().put("plate", m.plate).put("name", m.name).put("phone", m.phone)
                            .put("validTill", m.validTill ?: org.json.JSONObject.NULL).put("notes", m.notes))
                    }
                    put("monthlyUsers", arr)
                }.toString(2)
                val name = "vehicle_gate_settings.json"
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, name)
                            put(MediaStore.Downloads.MIME_TYPE, "application/json")
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: return@withContext "failed"
                        contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                        "Downloads/$name"
                    } else {
                        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                        File(dir, name).apply { writeText(json) }.absolutePath
                    }
                } catch (e: Exception) { "failed: ${e.message}" }
            }
            toast("Settings backed up to $where (contains camera passwords – keep it private)")
        }
    }

    /** The Add/Edit camera window currently open (to refresh its area after Preview). */
    private var openDialog: Pair<DialogCameraBinding, String>? = null

    override fun onResume() {
        super.onResume()
        if (::b.isInitialized) showProtection()
        // An area saved in Preview while the Edit window was open: show the new numbers in it.
        openDialog?.let { (d, id) ->
            app.settingsStore.cameras().firstOrNull { it.id == id }?.let { c ->
                d.roiL.setText(c.roiLeft.toString()); d.roiT.setText(c.roiTop.toString())
                d.roiR.setText(c.roiRight.toString()); d.roiB.setText(c.roiBottom.toString())
            }
        }
        // Reload every time: Preview may have saved a new search area for a camera.
        cameras.clear()
        cameras += app.settingsStore.cameras()
        renderCameras()
    }

    // ------------------------------------------------------------------ cameras

    private fun renderCameras() {
        refreshSummaries()
        b.cameraList.removeAllViews()
        if (cameras.isEmpty()) {
            val tv = android.widget.TextView(this).apply {
                text = "No cameras yet."
                setTextColor(getColor(com.vehiclegate.anpr.R.color.text_dim))
            }
            b.cameraList.addView(tv)
            return
        }
        cameras.forEachIndexed { index, cam ->
            val row = ItemCameraConfigBinding.inflate(LayoutInflater.from(this), b.cameraList, false)
            row.camName.text = (if (cam.enabled) "" else "[disabled] ") + cam.name
            row.camUrl.text = if (com.vehiclegate.anpr.camera.VlcPlayer.isTestVideo(cam.url))
                "Test video (plays on a loop) · ${File(android.net.Uri.parse(cam.url).path ?: "").length() / (1024 * 1024)} MB"
            else maskPassword(cam.url)
            row.preview.setOnClickListener { PreviewActivity.start(this, cam) }
            row.test.setOnClickListener { RtspTestDialog.show(this, cam.name, cam.url) }
            row.edit.setOnClickListener { editCamera(index) }
            row.delete.setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete ${cam.name}?")
                    .setPositiveButton("Delete") { _, _ ->
                        if (com.vehiclegate.anpr.camera.VlcPlayer.isTestVideo(cam.url)) {
                            android.net.Uri.parse(cam.url).path?.let { File(it).delete() }
                        }
                        cameras.removeAt(index); persistCameras()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            b.cameraList.addView(row.root)
        }
    }

    private fun maskPassword(url: String) = RtspTestDialog.maskPassword(url)

    private fun persistCameras() {
        app.settingsStore.saveCameras(cameras)
        renderCameras()
    }

    private fun editCamera(index: Int?) {
        val existing = index?.let { cameras[it] }
        val d = DialogCameraBinding.inflate(layoutInflater)
        val motionOptions = arrayOf("Off – scan continuously", "Low sensitivity", "Medium sensitivity", "High sensitivity")
        d.motion.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, motionOptions)

        val c = existing ?: CameraConfig(name = "Gate Cam ${cameras.size + 1}", url = "")
        d.name.setText(c.name)
        d.url.setText(c.url)
        d.enabled.isChecked = c.enabled
        d.tcp.isChecked = c.useTcp
        d.roiL.setText(c.roiLeft.toString())
        d.roiT.setText(c.roiTop.toString())
        d.roiR.setText(c.roiRight.toString())
        d.roiB.setText(c.roiBottom.toString())
        d.motion.setSelection(c.motionSensitivity.coerceIn(0, 3))

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Add camera" else "Edit camera")
            .setView(d.root)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updated = readCameraDialog(d, c) ?: return@setOnClickListener
                if (index == null) cameras += updated else cameras[index] = updated
                persistCameras()
                dialog.dismiss()
            }
        }
        openDialog = d to c.id
        dialog.setOnDismissListener { if (openDialog?.first === d) openDialog = null }
        // Test / preview what is typed in the dialog, before saving.
        d.dlgTest.setOnClickListener {
            readCameraDialog(d, c)?.let { RtspTestDialog.show(this, it.name, it.url) }
        }
        d.dlgPreview.setOnClickListener {
            readCameraDialog(d, c)?.let { PreviewActivity.start(this, it) }
        }
        dialog.show()
    }

    /** Builds a CameraConfig from the dialog fields, or shows a toast and returns null. */
    private fun readCameraDialog(d: DialogCameraBinding, base: CameraConfig): CameraConfig? {
        val url = d.url.text.toString().trim()
        if (!url.startsWith("rtsp://", ignoreCase = true) && !url.startsWith("rtsps://", ignoreCase = true) &&
            !com.vehiclegate.anpr.camera.VlcPlayer.isTestVideo(url)) {
            toast("URL must start with rtsp://"); return null
        }
        val l = d.roiL.int(0).coerceIn(0, 95)
        val t = d.roiT.int(0).coerceIn(0, 95)
        val r = d.roiR.int(100).coerceIn(l + 5, 100)
        val bt = d.roiB.int(100).coerceIn(t + 5, 100)
        return base.copy(
            name = d.name.text.toString().trim().ifEmpty { "Camera" },
            url = url,
            enabled = d.enabled.isChecked,
            useTcp = d.tcp.isChecked,
            roiLeft = l, roiTop = t, roiRight = r, roiBottom = bt,
            motionSensitivity = d.motion.selectedItemPosition
        )
    }

    private fun addNvrChannels() {
        val d = DialogNvrBinding.inflate(layoutInflater)
        d.prefix.setText("Gate Cam")
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Add NVR / DVR channels")
            .setView(d.root)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val template = d.template.text.toString().trim()
                if (!template.startsWith("rtsp", true) || !template.contains("{ch}")) {
                    toast("Template must be an rtsp:// URL containing {ch}"); return@setOnClickListener
                }
                val channels = parseChannels(d.channels.text.toString())
                if (channels.isEmpty()) { toast("Enter channels, e.g. 1,2,3 or 1-4"); return@setOnClickListener }
                val prefix = d.prefix.text.toString().trim().ifEmpty { "Cam" }
                channels.forEach { ch ->
                    cameras += CameraConfig(name = "$prefix $ch", url = template.replace("{ch}", ch.toString()))
                }
                persistCameras()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun parseChannels(s: String): List<Int> = s.split(',', ' ', ';')
        .filter { it.isNotBlank() }
        .flatMap { part ->
            val range = part.split('-').mapNotNull { it.trim().toIntOrNull() }
            when {
                range.size == 2 && range[0] <= range[1] && range[1] - range[0] < 64 -> (range[0]..range[1]).toList()
                range.size == 1 -> range
                else -> emptyList()
            }
        }.distinct()

    // ------------------------------------------------------------------ general settings

    private fun loadSettings(s: AppSettings) {
        b.siteTitle.setText(s.siteTitle)
        b.commonStates.setText(s.commonStates)
        b.feeAmount.setText(s.feeAmount.toString())
        b.feePeriodHours.setText(s.feePeriodHours.toString())
        b.minStayMinutes.setText(s.minStayMinutes.toString())
        b.strictStates.isChecked = s.strictStates
        b.useAiPlates.isChecked = s.useAiPlates
        b.plateCropOnly.isChecked = s.plateCropOnly
        b.voiceGroup.check(if (s.voiceLanguage == "en-IN") com.vehiclegate.anpr.R.id.voiceEnglish else com.vehiclegate.anpr.R.id.voiceTamil)
        b.keepPicturesDays.setText(s.keepPicturesDays.toString())
        b.themeGroup.check(when (s.themeMode) { 1 -> com.vehiclegate.anpr.R.id.themeLight; 2 -> com.vehiclegate.anpr.R.id.themeDark; else -> com.vehiclegate.anpr.R.id.themeSystem })
        b.movingOnly.isChecked = s.movingOnly
        b.cooldown.setText(s.cooldownSec.toString())
        b.minReads.setText(s.minReads.toString())
        b.settle.setText(s.settleMs.toString())
        b.ocrInterval.setText(s.ocrIntervalMs.toString())
        b.minConf.setText(s.minConfidencePct.toString())
        b.retention.setText(s.retentionDays.toString())
        b.beep.isChecked = s.beepOnEntry
        b.liveVideo.isChecked = s.showLiveVideo
    }

    /** Current uninstall protection of this device. */
    private fun showProtection() {
        refreshSummaries()
        val P = com.vehiclegate.anpr.admin.DeviceProtection
        val owner = P.isDeviceOwner(this)
        b.protectStatus.text = when {
            P.isKiosk(this) -> "Kiosk mode ON: uninstall is blocked and the device stays in the app. Leaving needs the admin PIN."
            owner -> "Set up for kiosk mode. Tick the box below to switch it on."
            P.isAdminActive(this) -> "Uninstall warning ON: the app can't be uninstalled until this is switched off here or in Android settings."
            else -> "Not protected. For a phone: switch on the uninstall warning. For a TV box / counter tablet used only " +
                "for parking: kiosk mode – set up once from a laptop with USB debugging on and no Google account on the device:\n" +
                P.ADB_COMMAND
        }
        b.protectAdmin.visibility = if (owner) android.view.View.GONE else android.view.View.VISIBLE
        b.protectAdmin.text = if (P.isAdminActive(this)) "Switch off the uninstall warning" else "Switch on the uninstall warning"
        b.protectKiosk.visibility = if (owner) android.view.View.VISIBLE else android.view.View.GONE
        b.protectKiosk.isChecked = P.isKiosk(this)
        b.protectRelease.visibility = if (owner) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ------------------------------------------------------------------ sections (two panels)

    private class Section(val key: String, val icon: Int, val title: String, val box: View,
                          val summary: () -> String, val warn: () -> Boolean = { false })

    private lateinit var sections: List<Section>
    private val rows = HashMap<String, View>()
    private var current = ""
    private var memberCount = 0
    private var userSummary = ""

    private fun setupSections() {
        val R = com.vehiclegate.anpr.R.drawable
        val backup = com.vehiclegate.anpr.backup.BackupStore(this)
        val P = com.vehiclegate.anpr.admin.DeviceProtection
        sections = listOf(
            Section("cameras", R.ic_videocam, "Cameras", b.secCameras,
                { if (cameras.isEmpty()) "No cameras added" else "${cameras.size} camera" + (if (cameras.size == 1) "" else "s") + " · ${cameras.count { it.enabled }} on" },
                { cameras.isEmpty() }),
            Section("parking", R.ic_parking, "Parking and fees", b.secParking,
                { "₹${b.feeAmount.text} per ${b.feePeriodHours.text} h" }),
            Section("monthly", R.ic_group, "Monthly users", b.secMonthly, { "$memberCount members" }),
            Section("users", R.ic_lock, "Users and roles", b.secUsers, { userSummary }),
            Section("detection", R.ic_search, "Plate detection", b.secDetection,
                { (if (b.useAiPlates.isChecked) "AI on" else "AI off") + " · " + b.commonStates.text.toString().replace(",", ", ") }),
            Section("display", R.ic_monitor, "Display and voice", b.secDisplay,
                { listOf(
                    when (b.themeGroup.checkedRadioButtonId) { com.vehiclegate.anpr.R.id.themeLight -> "Light"; com.vehiclegate.anpr.R.id.themeDark -> "Dark"; else -> "Same as device" },
                    if (b.voiceGroup.checkedRadioButtonId == com.vehiclegate.anpr.R.id.voiceEnglish) "English" else "Tamil + English"
                ).joinToString(" · ") }),
            Section("pictures", R.ic_photo, "Pictures and history", b.secPictures,
                { "Pictures kept ${b.keepPicturesDays.text} days" + if (b.plateCropOnly.isChecked) " · plate only" else "" }),
            Section("backup", R.ic_cloud, "Backup and sync", b.secBackup,
                { when {
                    !backup.ready -> "Email backup not set up"
                    !backup.enabled -> "Email backup off"
                    backup.lastResult.isNotEmpty() -> backup.lastResult
                    else -> "Email backup on"
                } },
                { !backup.ready || !backup.enabled || backup.lastResult.startsWith("Backup failed") }),
            Section("protection", R.ic_shield, "Device protection", b.secProtection,
                { when {
                    P.isKiosk(this) -> "Kiosk mode on"
                    P.isDeviceOwner(this) -> "Ready for kiosk mode"
                    P.isAdminActive(this) -> "Uninstall warning on"
                    else -> "Not protected"
                } },
                { !P.isKiosk(this) && !P.isAdminActive(this) }),
            Section("about", R.ic_info, "About", b.secAbout, { "Build ${aboutBuild()}" })
        )
        b.sectionList.removeAllViews()
        for (s in sections) {
            val row = layoutInflater.inflate(com.vehiclegate.anpr.R.layout.item_settings_section, b.sectionList, false)
            row.findViewById<android.widget.ImageView>(com.vehiclegate.anpr.R.id.icon).setImageResource(s.icon)
            row.findViewById<TextView>(com.vehiclegate.anpr.R.id.title).text = s.title
            row.setOnClickListener { select(s.key) }
            b.sectionList.addView(row)
            rows[s.key] = row
        }
        b.advancedToggle.setOnClickListener {
            val open = b.advancedBox.visibility != View.VISIBLE
            b.advancedBox.visibility = if (open) View.VISIBLE else View.GONE
            b.advancedToggle.text = if (open) "Hide advanced" else "Advanced"
        }
        b.aboutText.text = buildString {
            appendLine("Vehicle Entry Monitor – build ${aboutBuild()}")
            val u = app.session.user
            if (u != null) appendLine("Logged in: ${u.name} (${com.vehiclegate.anpr.data.Role.label(u.role)})")
            appendLine("Cameras on this device: ${cameras.count { it.enabled }}")
            append("Package: $packageName")
        }
        b.checkUpdate.setOnClickListener {
            try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://github.com/kowshick-s/ANPR/releases/latest"))) }
            catch (_: Exception) { toast("No browser on this device") }
        }
        lifecycleScope.launch {
            memberCount = withContext(Dispatchers.IO) { app.db.members().all().size }
            val users = withContext(Dispatchers.IO) { app.db.users().active() }
            userSummary = listOf(
                com.vehiclegate.anpr.data.Role.ADMIN to "admin", com.vehiclegate.anpr.data.Role.STAFF to "staff",
                com.vehiclegate.anpr.data.Role.TV to "TV"
            ).joinToString(" · ") { (r, l) -> "${users.count { it.role == r }} $l" }
            refreshSummaries()
        }
        select(intent.getStringExtra(EXTRA_SECTION) ?: "cameras")
    }

    private fun aboutBuild(): String = try {
        @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionCode.toString()
    } catch (_: Exception) { "?" }

    /** Show one section on the right (saving what was changed in the previous one). */
    private fun select(key: String) {
        if (current.isNotEmpty() && current != key) save(quiet = true)
        current = key
        for (s in sections) {
            s.box.visibility = if (s.key == key) View.VISIBLE else View.GONE
            rows[s.key]?.isSelected = s.key == key
        }
        sections.firstOrNull { it.key == key }?.let { b.paneTitle.text = it.title }
        b.pane.scrollTo(0, 0)
        refreshSummaries()
    }

    private fun refreshSummaries() {
        if (!::sections.isInitialized) return
        for (s in sections) {
            val row = rows[s.key] ?: continue
            val text = try { s.summary() } catch (_: Exception) { "" }
            row.findViewById<TextView>(com.vehiclegate.anpr.R.id.summary).text = text
            row.findViewById<View>(com.vehiclegate.anpr.R.id.warn).visibility = if (s.warn()) View.VISIBLE else View.GONE
            if (s.key == current) b.paneSummary.text = text
        }
    }

    override fun onPause() {
        super.onPause()
        if (::b.isInitialized && !isFinishing) save(quiet = true)
    }

    private fun save(quiet: Boolean = false) {
        com.vehiclegate.anpr.backup.BackupStore(this).apply {
            enabled = b.backupEnabled.isChecked
            gmail = b.backupGmail.text.toString()
            alsoTo = b.backupAlsoTo.text.toString()
            b.backupPassword.text.toString().takeIf { it.isNotBlank() }?.let { appPassword = it }
        }
        val d = AppSettings()
        app.settingsStore.saveSettings(
            AppSettings(
                cooldownSec = b.cooldown.int(d.cooldownSec).coerceIn(5, 86_400),
                minReads = b.minReads.int(d.minReads).coerceIn(1, 20),
                settleMs = b.settle.int(d.settleMs).coerceIn(500, 20_000),
                ocrIntervalMs = b.ocrInterval.int(d.ocrIntervalMs).coerceIn(100, 5_000),
                minConfidencePct = b.minConf.int(d.minConfidencePct).coerceIn(0, 100),
                beepOnEntry = b.beep.isChecked,
                showLiveVideo = b.liveVideo.isChecked,
                retentionDays = b.retention.int(d.retentionDays).coerceIn(0, 3650),
                siteTitle = b.siteTitle.text.toString().trim().ifEmpty { d.siteTitle },
                commonStates = com.vehiclegate.anpr.core.IndianPlateParser.parseStateList(b.commonStates.text.toString())
                    .joinToString(",").ifEmpty { d.commonStates },
                strictStates = b.strictStates.isChecked,
                useAiPlates = b.useAiPlates.isChecked,
                feeAmount = b.feeAmount.int(d.feeAmount).coerceIn(0, 100_000),
                feePeriodHours = b.feePeriodHours.int(d.feePeriodHours).coerceIn(1, 24 * 31),
                minStayMinutes = b.minStayMinutes.int(d.minStayMinutes).coerceIn(0, 24 * 60),
                movingOnly = b.movingOnly.isChecked,
                themeMode = themeMode(),
                plateCropOnly = b.plateCropOnly.isChecked,
                voiceLanguage = if (b.voiceGroup.checkedRadioButtonId == com.vehiclegate.anpr.R.id.voiceEnglish) "en-IN" else "ta-IN",
                keepPicturesDays = b.keepPicturesDays.int(d.keepPicturesDays).coerceIn(0, 3650)
            )
        )
        app.settingsStore.saveCameras(cameras)
        if (!quiet) toast("Saved")
        AnprApp.applyTheme(themeMode())
        refreshSummaries()
    }

    private fun themeMode() = when (b.themeGroup.checkedRadioButtonId) {
        com.vehiclegate.anpr.R.id.themeLight -> 1
        com.vehiclegate.anpr.R.id.themeDark -> 2
        else -> 0
    }

    override fun onBackPressed() {
        save()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear all vehicle history?")
            .setMessage("This deletes every logged entry and snapshot.")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        app.db.entries().clear()
                        File(filesDir, "snapshots").listFiles()?.forEach { it.delete() }
                    }
                    toast("History cleared")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportCsv() {
        lifecycleScope.launch {
            val where = withContext(Dispatchers.IO) {
                val rows = app.db.entries().all()
                val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val tf = SimpleDateFormat("HH:mm:ss", Locale.US)
                val csv = buildString {
                    append("Vehicle Number,Type,Token,Name,Date In,Time In,Status,Date Out,Time Out,Amount,Confidence %,Reads,Cameras\n")
                    fun q(v: String?) = "\"" + (v ?: "").replace("\"", "'") + "\""
                    rows.forEach { e ->
                        val out = e.exitTime ?: e.exitDetectedAt
                        append(listOf(
                            e.plateFormatted, if (e.isMonthly) "Monthly" else "Daily", q(e.token), q(e.name),
                            df.format(Date(e.entryTime)), tf.format(Date(e.entryTime)), e.state,
                            out?.let { df.format(Date(it)) } ?: "", out?.let { tf.format(Date(it)) } ?: "",
                            (e.amount ?: "").toString(), (e.confidence * 100).toInt().toString(),
                            e.totalReads.toString(), e.cameraCount.toString()
                        ).joinToString(",")).append("\n")
                    }
                }
                val name = "vehicle_entries_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, name)
                            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: return@withContext "export failed"
                        contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                        "Downloads/$name (${rows.size} rows)"
                    } else {
                        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                        val f = File(dir, name)
                        f.writeText(csv)
                        "${f.absolutePath} (${rows.size} rows)"
                    }
                } catch (e: Exception) {
                    "export failed: ${e.message}"
                }
            }
            toast("Exported to $where")
        }
    }

    private fun android.widget.EditText.int(default: Int) = text.toString().trim().toIntOrNull() ?: default
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        /** Opened after an admin PIN was typed (staff logged in). */
        const val EXTRA_ADMIN_OK = "adminOk"
        /** Section to open first, e.g. "backup" */
        const val EXTRA_SECTION = "section"
    }
}
