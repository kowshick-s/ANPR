package com.vehiclegate.anpr.ui

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
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

    private fun save() {
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
                themeMode = themeMode()
            )
        )
        app.settingsStore.saveCameras(cameras)
        toast("Saved")
        AnprApp.applyTheme(themeMode())
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
}
