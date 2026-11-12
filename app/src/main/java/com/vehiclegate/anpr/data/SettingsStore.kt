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
    val siteTitle: String = "Vehicle Entry Monitor",
    /** State codes most vehicles at this site come from – preferred when correcting OCR errors. */
    val commonStates: String = com.vehiclegate.anpr.core.IndianPlateParser.DEFAULT_PREFERRED,
    /** Reject plates whose state is not in [commonStates], and require the full plate format. */
    val strictStates: Boolean = true,
    /** Log only plates that were moving (vehicle being ridden/driven), ignore parked vehicles. */
    val movingOnly: Boolean = true,
    /** Parking fee in rupees for every started period of [feePeriodHours]. */
    val feeAmount: Int = 10,
    val feePeriodHours: Int = 24,
    /** A parked vehicle seen again sooner than this is still at the gate, not leaving. */
    val minStayMinutes: Int = 10
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
            siteTitle = prefs.getString("siteTitle", d.siteTitle) ?: d.siteTitle,
            commonStates = prefs.getString("commonStates", d.commonStates) ?: d.commonStates,
            strictStates = prefs.getBoolean("strictStates", d.strictStates),
            movingOnly = prefs.getBoolean("movingOnly", d.movingOnly),
            feeAmount = prefs.getInt("feeAmount", d.feeAmount),
            feePeriodHours = prefs.getInt("feePeriodHours", d.feePeriodHours),
            minStayMinutes = prefs.getInt("minStayMinutes", d.minStayMinutes)
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
            .putString("commonStates", s.commonStates)
            .putBoolean("strictStates", s.strictStates)
            .putBoolean("movingOnly", s.movingOnly)
            .putInt("feeAmount", s.feeAmount)
            .putInt("feePeriodHours", s.feePeriodHours)
            .putInt("minStayMinutes", s.minStayMinutes)
            .apply()
    }

    /** Everything needed to restore this setup on a new install: cameras + settings. */
    fun exportJson(): String {
        val st = settings()
        val cams = JSONArray().also { a -> cameras().forEach { a.put(it.toJson()) } }
        val set = JSONObject()
            .put("cooldownSec", st.cooldownSec).put("minReads", st.minReads)
            .put("settleMs", st.settleMs).put("ocrIntervalMs", st.ocrIntervalMs)
            .put("minConfidencePct", st.minConfidencePct).put("beepOnEntry", st.beepOnEntry)
            .put("retentionDays", st.retentionDays).put("showLiveVideo", st.showLiveVideo)
            .put("siteTitle", st.siteTitle).put("commonStates", st.commonStates)
            .put("strictStates", st.strictStates).put("movingOnly", st.movingOnly)
            .put("feeAmount", st.feeAmount).put("feePeriodHours", st.feePeriodHours)
            .put("minStayMinutes", st.minStayMinutes)
        return JSONObject().put("app", "VehicleGateANPR").put("format", 1)
            .put("cameras", cams).put("settings", set).toString(2)
    }

    /** Restores an [exportJson] backup. Returns the number of cameras imported. */
    fun importJson(text: String): Int {
        val o = JSONObject(text)
        require(o.optString("app") == "VehicleGateANPR") { "not a Vehicle Gate ANPR settings file" }
        val arr = o.getJSONArray("cameras")
        val cams = (0 until arr.length()).map { CameraConfig.fromJson(arr.getJSONObject(it)) }
        saveCameras(cams)
        o.optJSONObject("settings")?.let { j ->
            val d = AppSettings()
            saveSettings(AppSettings(
                cooldownSec = j.optInt("cooldownSec", d.cooldownSec),
                minReads = j.optInt("minReads", d.minReads),
                settleMs = j.optInt("settleMs", d.settleMs),
                ocrIntervalMs = j.optInt("ocrIntervalMs", d.ocrIntervalMs),
                minConfidencePct = j.optInt("minConfidencePct", d.minConfidencePct),
                beepOnEntry = j.optBoolean("beepOnEntry", d.beepOnEntry),
                retentionDays = j.optInt("retentionDays", d.retentionDays),
                showLiveVideo = j.optBoolean("showLiveVideo", d.showLiveVideo),
                siteTitle = j.optString("siteTitle", d.siteTitle),
                commonStates = j.optString("commonStates", d.commonStates),
                strictStates = j.optBoolean("strictStates", d.strictStates),
                movingOnly = j.optBoolean("movingOnly", d.movingOnly),
                feeAmount = j.optInt("feeAmount", d.feeAmount),
                feePeriodHours = j.optInt("feePeriodHours", d.feePeriodHours),
                minStayMinutes = j.optInt("minStayMinutes", d.minStayMinutes)
            ))
        }
        return cams.size
    }

    companion object { private const val KEY_CAMERAS = "cameras" }
}
