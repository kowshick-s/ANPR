package com.vehiclegate.anpr.camera

import android.content.Context
import android.net.Uri
import android.view.TextureView
import com.vehiclegate.anpr.core.RtspProbe
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout

/**
 * Thin wrapper around LibVLC (the engine inside the VLC app) that plays one RTSP URL into a
 * TextureView. LibVLC copes with cameras/NVRs that Android's own player rejects, e.g. SDP
 * without "fmtp" (codec settings sent in-band), odd auth, H.265, large 4K frames.
 *
 * The video is always rendered at the camera's native resolution (window size = video size),
 * so TextureView.getBitmap(w, h) returns full-detail frames for OCR even when the view is tiny.
 * All calls and callbacks are on the main thread.
 */
class VlcPlayer(
    context: Context,
    private val textureView: TextureView,
    private val listener: Listener
) {
    interface Listener {
        fun onVideoSize(width: Int, height: Int) {}
        fun onFirstFrame() {}
        /** Playback clock advanced – used as a "stream is alive" heartbeat. */
        fun onProgress() {}
        fun onBuffering(percent: Float) {}
        fun onError(message: String) {}
        fun onEnded() {}
    }

    data class Stats(val displayedFrames: Int, val lostFrames: Int, val inputKbps: Int)

    private val libVlc: LibVLC = shared(context.applicationContext)
    private var player: MediaPlayer? = null
    var videoWidth = 0; private set
    var videoHeight = 0; private set
    private var gotFirstFrame = false

    fun play(rawUrl: String, useTcp: Boolean) {
        release()
        gotFirstFrame = false

        // Credentials go in as options, not in the URL – no URL-escaping problems at all.
        val target = try { RtspProbe.parseUrl(rawUrl) } catch (e: Exception) {
            listener.onError("invalid URL: ${e.message}"); return
        }
        val media = Media(libVlc, Uri.parse(target.requestUrl))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=$NETWORK_CACHING_MS")
        media.addOption(":no-audio")
        if (useTcp) media.addOption(":rtsp-tcp")
        target.user?.let { media.addOption(":rtsp-user=$it") }
        target.password?.let { media.addOption(":rtsp-pwd=$it") }

        val mp = MediaPlayer(libVlc)
        mp.media = media
        media.release()

        val vout = mp.vlcVout
        vout.setVideoView(textureView)
        vout.attachViews(IVLCVout.OnNewVideoLayoutListener { v, width, height, _, _, _, _ ->
            if (width > 0 && height > 0 && (width != videoWidth || height != videoHeight)) {
                videoWidth = width
                videoHeight = height
                v.setWindowSize(width, height) // render at native resolution
                listener.onVideoSize(width, height)
            }
        })
        vout.setWindowSize(INITIAL_W, INITIAL_H)

        mp.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Vout -> if (e.voutCount > 0 && !gotFirstFrame) {
                    gotFirstFrame = true
                    listener.onFirstFrame()
                }
                MediaPlayer.Event.TimeChanged, MediaPlayer.Event.PositionChanged -> {
                    listener.onProgress()
                    if (!gotFirstFrame && mp.vlcVout.areViewsAttached() && videoWidth > 0) {
                        gotFirstFrame = true
                        listener.onFirstFrame()
                    }
                }
                MediaPlayer.Event.Buffering -> listener.onBuffering(e.buffering)
                MediaPlayer.Event.EncounteredError ->
                    listener.onError("VLC could not open or decode the stream")
                MediaPlayer.Event.EndReached, MediaPlayer.Event.Stopped ->
                    if (player === mp) listener.onEnded()
            }
        }
        player = mp
        mp.play()
    }

    /** Codec FourCC (h264 / hevc …), resolution and frame rate of the playing track. */
    fun trackInfo(): String? {
        val t = player?.currentVideoTrack ?: return null
        val fps = if (t.frameRateDen > 0) t.frameRateNum.toFloat() / t.frameRateDen else 0f
        return "${t.codec?.uppercase() ?: "?"} ${t.width}x${t.height}" +
            if (fps > 0) " @${"%.0f".format(fps)}fps" else ""
    }

    fun stats(): Stats? {
        val m = player?.media ?: return null
        return try {
            val s = m.stats ?: return null
            Stats(s.displayedPictures, s.lostPictures, (s.inputBitrate * 8000).toInt())
        } finally {
            m.release()
        }
    }

    fun release() {
        val mp = player ?: return
        player = null
        mp.setEventListener(null)
        try { mp.stop() } catch (_: Exception) {}
        try { mp.vlcVout.detachViews() } catch (_: Exception) {}
        mp.release()
    }

    companion object {
        private const val NETWORK_CACHING_MS = 300
        private const val INITIAL_W = 1920
        private const val INITIAL_H = 1080

        @Volatile private var instance: LibVLC? = null

        /** One LibVLC instance for the whole app (it is heavy to create). */
        fun shared(context: Context): LibVLC = instance ?: synchronized(this) {
            instance ?: LibVLC(context, arrayListOf(
                "--no-audio",
                "--network-caching=$NETWORK_CACHING_MS",
                "--rtsp-frame-buffer-size=4000000", // large 4K/5MP I-frames
                "--drop-late-frames",
                "--skip-frames",
                "--no-sub-autodetect-file",
                "-v"
            )).also { instance = it }
        }
    }
}
