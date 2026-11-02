package com.vehiclegate.anpr.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CameraConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** e.g. rtsp://admin:pass@192.168.1.64:554/Streaming/Channels/101 */
    val url: String,
    val enabled: Boolean = true,
    /** RTP over TCP (interleaved). Much more reliable than UDP on most networks. */
    val useTcp: Boolean = true,
    /** Region of interest in percent of the frame – limit OCR to where plates appear. */
    val roiLeft: Int = 0,
    val roiTop: Int = 0,
    val roiRight: Int = 100,
    val roiBottom: Int = 100,
    /** 0 = always scan (no motion gate), 1 = low, 2 = medium, 3 = high sensitivity */
    val motionSensitivity: Int = 2
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", url).put("enabled", enabled)
        .put("useTcp", useTcp).put("roiLeft", roiLeft).put("roiTop", roiTop)
        .put("roiRight", roiRight).put("roiBottom", roiBottom)
        .put("motionSensitivity", motionSensitivity)

    companion object {
        fun fromJson(o: JSONObject) = CameraConfig(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "Camera"),
            url = o.optString("url", ""),
            enabled = o.optBoolean("enabled", true),
            useTcp = o.optBoolean("useTcp", true),
            roiLeft = o.optInt("roiLeft", 0),
            roiTop = o.optInt("roiTop", 0),
            roiRight = o.optInt("roiRight", 100),
            roiBottom = o.optInt("roiBottom", 100),
            motionSensitivity = o.optInt("motionSensitivity", 2)
        )
    }
}

data class AppSettings(
    /** Same plate within this window = same visit, not a new entry. */
    val cooldownSec: Int = 120,
    /** Reads needed (across all cameras) to accept a vehicle. */
    val minReads: Int = 2,
    /** Vehicle decision is made after this long with no new reads. */
    val settleMs: Int = 2500,
    /** How often each camera runs OCR while motion is present. */
    val ocrIntervalMs: Int = 400,
    /** Fused confidence below this is discarded (percent). */
    val minConfidencePct: Int = 45,
    val beepOnEntry: Boolean = true,
    val retentionDays: Int = 30,
    /** Live video in the dashboard tiles. Off = compact status chips (detection still runs). */
    val showLiveVideo: Boolean = false,
    val siteTitle: String = "Vehicle Entry Monitor"
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("vehicle_gate_settings", Context.MODE_PRIVATE)

    fun cameras(): List<CameraConfig> {
        val raw = prefs.getString(KEY_CAMERAS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { CameraConfig.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCameras(list: List<CameraConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_CAMERAS, arr.toString()).apply()
    }

    fun settings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            cooldownSec = prefs.getInt("cooldownSec", d.cooldownSec),
            minReads = prefs.getInt("minReads", d.minReads),
            settleMs = prefs.getInt("settleMs", d.settleMs),
            ocrIntervalMs = prefs.getInt("ocrIntervalMs", d.ocrIntervalMs),
            minConfidencePct = prefs.getInt("minConfidencePct", d.minConfidencePct),
            beepOnEntry = prefs.getBoolean("beepOnEntry", d.beepOnEntry),
            retentionDays = prefs.getInt("retentionDays", d.retentionDays),
            showLiveVideo = prefs.getBoolean("showLiveVideo", d.showLiveVideo),
            siteTitle = prefs.getString("siteTitle", d.siteTitle) ?: d.siteTitle
        )
    }

    fun saveSettings(s: AppSettings) {
        prefs.edit()
            .putInt("cooldownSec", s.cooldownSec)
            .putInt("minReads", s.minReads)
            .putInt("settleMs", s.settleMs)
            .putInt("ocrIntervalMs", s.ocrIntervalMs)
            .putInt("minConfidencePct", s.minConfidencePct)
            .putBoolean("beepOnEntry", s.beepOnEntry)
            .putInt("retentionDays", s.retentionDays)
            .putBoolean("showLiveVideo", s.showLiveVideo)
            .putString("siteTitle", s.siteTitle)
            .apply()
    }

    companion object { private const val KEY_CAMERAS = "cameras" }
}
