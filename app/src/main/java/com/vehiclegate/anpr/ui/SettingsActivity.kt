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
        b.save.setOnClickListener { save(); finish() }
        b.clearHistory.setOnClickListener { confirmClear() }
        b.exportCsv.setOnClickListener { exportCsv() }
        b.addCamera.requestFocus()
    }

    override fun onResume() {
        super.onResume()
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
            row.camUrl.text = maskPassword(cam.url)
            row.preview.setOnClickListener { PreviewActivity.start(this, cam) }
            row.test.setOnClickListener { RtspTestDialog.show(this, cam.name, cam.url) }
            row.edit.setOnClickListener { editCamera(index) }
            row.delete.setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete ${cam.name}?")
                    .setPositiveButton("Delete") { _, _ -> cameras.removeAt(index); persistCameras() }
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
        if (!url.startsWith("rtsp://", ignoreCase = true) && !url.startsWith("rtsps://", ignoreCase = true)) {
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
                siteTitle = b.siteTitle.text.toString().trim().ifEmpty { d.siteTitle }
            )
        )
        app.settingsStore.saveCameras(cameras)
        toast("Saved")
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
                    append("Vehicle Number,Date,Entry Time,Confidence %,Reads,Cameras,Per-camera reads\n")
                    rows.forEach { e ->
                        val perCam = e.cameraSummary.lines().joinToString(" ; ") { it.replace("|", " ") }
                        append("${e.plateFormatted},${df.format(Date(e.entryTime))},${tf.format(Date(e.entryTime))}," +
                            "${(e.confidence * 100).toInt()},${e.totalReads},${e.cameraCount},\"${perCam.replace("\"", "'")}\"\n")
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
