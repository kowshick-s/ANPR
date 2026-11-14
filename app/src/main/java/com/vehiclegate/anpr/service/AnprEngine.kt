package com.vehiclegate.anpr.service

import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import com.vehiclegate.anpr.AnprApp
import com.vehiclegate.anpr.AnprPipeline
import com.vehiclegate.anpr.camera.CameraStream
import com.vehiclegate.anpr.data.VehicleEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Owns the running detection pipeline for the whole app process. Started/stopped by
 * [AnprService] (so it keeps running in the background); screens just attach as listeners.
 * Keeps the latest per-camera state so a screen opened later can show it immediately.
 * All calls on the main thread.
 */
object AnprEngine {

    class CamState {
        var status: CameraStream.Status = CameraStream.Status.CONNECTING
        var message: String? = null
        var scans = 0
        var motion = false
        var reads = 0
        var picture: Bitmap? = null
    }

    val camStates = ConcurrentHashMap<String, CamState>()
    var lastSkipped: String? = null; private set
    var lastVehicle: VehicleEntry? = null; private set
    var cameraCount = 0; private set

    /** The service updates its notification through this. */
    var onSummaryChanged: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pipeline: AnprPipeline? = null
    private var configKey: String? = null
    private val listeners = CopyOnWriteArraySet<AnprPipeline.Events>()
    private var tone: ToneGenerator? = null
    private var beep = false

    val isRunning: Boolean get() = pipeline != null

    fun addListener(l: AnprPipeline.Events) { listeners += l }
    fun removeListener(l: AnprPipeline.Events) { listeners -= l }

    /** Starts the pipeline, or restarts it if cameras/settings changed. Returns true if (re)started. */
    fun startOrReload(app: AnprApp): Boolean {
        val store = app.settingsStore
        val key = store.exportJson()
        if (configKey == key) return false
        stop()
        configKey = key
        val settings = store.settings()
        val cams = store.cameras().filter { it.enabled && it.url.isNotBlank() }
        cameraCount = cams.size
        camStates.clear()
        cams.forEach { camStates[it.id] = CamState() }
        beep = settings.beepOnEntry
        if (beep) tone = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) } catch (e: Exception) { null }
        if (cams.isNotEmpty()) {
            pipeline = AnprPipeline(app, scope, dispatcher).also { it.start(cams, settings) }
        }
        onSummaryChanged?.invoke()
        return true
    }

    fun stop() {
        pipeline?.stop()
        pipeline = null
        configKey = null
        tone?.release()
        tone = null
    }

    private val dispatcher = object : AnprPipeline.Events {
        override fun onCameraStatus(cameraId: String, status: CameraStream.Status, message: String?) {
            camStates[cameraId]?.let { it.status = status; it.message = message }
            listeners.forEach { it.onCameraStatus(cameraId, status, message) }
        }

        override fun onRawRead(cameraId: String, plate: String, scorePct: Int) {
            camStates[cameraId]?.let { it.reads++ }
            listeners.forEach { it.onRawRead(cameraId, plate, scorePct) }
        }

        override fun onSkipped(plate: String, reason: String) {
            lastSkipped = "$plate – $reason"
            listeners.forEach { it.onSkipped(plate, reason) }
        }

        override fun onCameraPicture(cameraId: String, picture: Bitmap) {
            camStates[cameraId]?.picture = picture
            listeners.forEach { it.onCameraPicture(cameraId, picture) }
        }

        override fun onCameraScan(cameraId: String, scans: Int, motion: Boolean) {
            camStates[cameraId]?.let { it.scans = scans; it.motion = motion }
            listeners.forEach { it.onCameraScan(cameraId, scans, motion) }
        }

        override fun onCaptured(capture: com.vehiclegate.anpr.data.Capture, isNew: Boolean) {
            if (beep && isNew) try { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 300) } catch (_: Exception) {}
            listeners.forEach { it.onCaptured(capture, isNew) }
            if (isNew) onSummaryChanged?.invoke()
        }

        override fun onAutoCheckedOut(entry: VehicleEntry) {
            listeners.forEach { it.onAutoCheckedOut(entry) }
        }

        override fun onVehicleSaved(entry: VehicleEntry) {
            lastVehicle = entry
            if (beep) try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250) } catch (_: Exception) {}
            listeners.forEach { it.onVehicleSaved(entry) }
            onSummaryChanged?.invoke()
        }
    }
}
