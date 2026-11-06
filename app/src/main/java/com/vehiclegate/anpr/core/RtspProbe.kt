package com.vehiclegate.anpr.core

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Low-level RTSP "handshake" test for one camera URL. Independent of the video player, so it
 * shows exactly which stage fails:
 *
 *   URL parse → DNS → TCP connect → OPTIONS → DESCRIBE (+ Basic/Digest auth) → SDP (codec,
 *   resolution, fps) → SETUP (RTP over TCP) → PLAY → receive video packets (bitrate) → TEARDOWN
 *
 * Pure JVM code (no Android APIs) – blocking, call it from a background thread.
 */
class RtspProbe(
    private val url: String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 5_000,
    private val mediaWaitMs: Long = 3_000
) {
    data class Step(val name: String, val ok: Boolean, val detail: String, val ms: Long)

    data class Result(
        val success: Boolean,
        val summary: String,
        val hint: String?,
        val steps: List<Step>,
        val videoCodec: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Float = 0f,
        val auth: String? = null,
        val kbps: Int = 0,
        val totalMs: Long = 0
    ) {
        fun report(): String = buildString {
            for (s in steps) {
                append(if (s.ok) "✔ " else "✘ ")
                append(s.name.padEnd(16))
                append(s.detail)
                if (s.ms > 0) append("  (${s.ms} ms)")
                append('\n')
            }
        }
    }

    // ------------------------------------------------------------------ public

    fun run(): Result {
        val started = System.currentTimeMillis()
        val steps = ArrayList<Step>()
        var codec: String? = null
        var width = 0
        var height = 0
        var fps = 0f
        var kbps = 0
        var conn: Conn? = null

        fun fail(hint: String): Result {
            conn?.closeQuietly()
            val failed = steps.lastOrNull { !it.ok }
            return Result(false, "FAILED at ${failed?.name ?: "start"}: ${failed?.detail ?: ""}", hint, steps,
                codec, width, height, fps, conn?.authType, kbps, System.currentTimeMillis() - started)
        }

        // 1. URL
        val target = try {
            parseUrl(url)
        } catch (e: Exception) {
            steps += Step("Parse URL", false, e.message ?: "invalid URL", 0)
            return fail("URL must look like rtsp://user:password@192.168.1.64:554/path")
        }
        steps += Step("Parse URL", true,
            "${target.host}:${target.port}${target.pathQuery}  user=${target.user ?: "(none)"}", 0)
        if (target.warning != null) steps += Step("URL check", true, "⚠ ${target.warning}", 0)

        // 2. DNS
        var t0 = now()
        val address = try {
            InetAddress.getByName(target.host)
        } catch (e: UnknownHostException) {
            steps += Step("DNS", false, "cannot resolve ${target.host}", now() - t0)
            return fail("Host name not found. Use the camera's IP address.")
        }
        steps += Step("DNS", true, address.hostAddress ?: target.host, now() - t0)

        // 3. TCP
        t0 = now()
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(address, target.port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
        } catch (e: Exception) {
            val ms = now() - t0
            steps += Step("TCP connect", false, "${address.hostAddress}:${target.port} – ${e.javaClass.simpleName}", ms)
            try { socket.close() } catch (_: Exception) {}
            return fail(when (e) {
                is SocketTimeoutException -> "No answer from ${address.hostAddress}:${target.port}. Check IP address, that the TV box is on the same network/VLAN as the camera, and firewall rules."
                is ConnectException -> "Connection refused – RTSP is disabled on the camera/NVR or the port is wrong (default 554)."
                is NoRouteToHostException -> "No route to host – the camera's subnet is not reachable from the TV box."
                else -> "Network error: ${e.message}"
            })
        }
        steps += Step("TCP connect", true, "${address.hostAddress}:${target.port}", now() - t0)

        val activeSocket: Socket = if (target.secure) {
            t0 = now()
            try {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(socket, target.host, target.port, true) as SSLSocket
                ssl.soTimeout = readTimeoutMs
                ssl.startHandshake()
                steps += Step("TLS", true, ssl.session.protocol, now() - t0)
                ssl
            } catch (e: Exception) {
                steps += Step("TLS", false, e.message ?: "TLS handshake failed", now() - t0)
                try { socket.close() } catch (_: Exception) {}
                return fail("rtsps:// TLS failed – the camera probably uses a self-signed certificate. Use rtsp:// instead.")
            }
        } else socket

        val c = Conn(activeSocket, target)
        conn = c
        try {
            // 4. OPTIONS
            t0 = now()
            val opt = c.request("OPTIONS", target.requestUrl)
            if (opt.code == 401) {
                // a few cameras require auth even for OPTIONS – DESCRIBE handles it below
                steps += Step("OPTIONS", true, "401 (auth required)", now() - t0)
            } else {
                steps += Step("OPTIONS", opt.code == 200,
                    "${opt.code} ${opt.reason}" + (opt.header("Server")?.let { "  server=$it" } ?: ""), now() - t0)
                if (opt.code != 200 && opt.code != 401) return fail("Server answered but is not a normal RTSP server (${opt.code}).")
            }

            // 5. DESCRIBE (+ auth)
            t0 = now()
            val desc = c.request("DESCRIBE", target.requestUrl, listOf("Accept" to "application/sdp"))
            val ms = now() - t0
            when (desc.code) {
                200 -> steps += Step("DESCRIBE", true, "200 OK" + (c.authType?.let { "  auth=$it" } ?: "  (no auth)"), ms)
                401, 403 -> {
                    steps += Step("DESCRIBE", false, "${desc.code} ${desc.reason}" + (c.authType?.let { "  tried $it" } ?: ""), ms)
                    return fail(if (target.user == null)
                        "Camera needs a login: rtsp://USER:PASSWORD@ip:port/path"
                    else
                        "Wrong username or password, or this user has no RTSP/live-view permission. (Hikvision/Dahua lock the account for ~30 min after several failures.)")
                }
                404 -> {
                    steps += Step("DESCRIBE", false, "404 Not Found", ms)
                    return fail("Stream path/channel not found. Check the path, e.g. Hikvision /Streaming/Channels/101, Dahua /cam/realmonitor?channel=1&subtype=0")
                }
                else -> {
                    steps += Step("DESCRIBE", false, "${desc.code} ${desc.reason}", ms)
                    return fail("Camera rejected the stream request (${desc.code}).")
                }
            }

            // 6. SDP
            val sdp = Sdp.parse(desc.bodyText())
            val video = sdp.video
            if (video == null) {
                steps += Step("SDP", false, "no video track", 0)
                return fail("The stream has no video track – check the channel number.")
            }
            codec = video.codec
            width = video.width; height = video.height; fps = video.fps
            steps += Step("SDP", true, buildString {
                append("video ${video.codec}")
                if (video.width > 0) append(" ${video.width}x${video.height}")
                if (video.fps > 0) append(" @${fmt(video.fps)}fps")
                if (sdp.audioCodec != null) append(", audio ${sdp.audioCodec}")
            }, 0)
            if (video.codec !in setOf("H264", "H265", "HEVC")) {
                steps += Step("Codec check", false, "${video.codec} – set the camera to H.264", 0)
            } else if (video.codec != "H264") {
                steps += Step("Codec check", true, "⚠ H.265 works on most boxes; if preview is black, switch the camera to H.264", 0)
            }

            // 7. SETUP (RTP over TCP, interleaved)
            val base = desc.header("Content-Base") ?: desc.header("Content-Location") ?: target.requestUrl
            val controlUrl = resolveControl(base, video.control)
            t0 = now()
            val setup = c.request("SETUP", controlUrl, listOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"))
            if (setup.code != 200) {
                steps += Step("SETUP (TCP)", false, "${setup.code} ${setup.reason}", now() - t0)
                return fail(if (setup.code == 461) "Camera does not support RTP over TCP – untick 'Use TCP transport' for this camera."
                else "Camera refused to set up the stream (${setup.code}). Too many viewers connected?")
            }
            val session = setup.header("Session")?.substringBefore(';')?.trim()
            val transport = setup.header("Transport") ?: ""
            val channel = Regex("interleaved=(\\d+)").find(transport)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            steps += Step("SETUP (TCP)", true, "session=${session ?: "?"} channel=$channel", now() - t0)

            // 8. PLAY + media
            t0 = now()
            val sessionHeader = listOfNotNull(session?.let { "Session" to it })
            val playUrl = sdp.sessionControl?.let { resolveControl(base, it) } ?: target.requestUrl
            val play = c.request("PLAY", playUrl, sessionHeader + ("Range" to "npt=0.000-"))
            if (play.code != 200) {
                steps += Step("PLAY", false, "${play.code} ${play.reason}", now() - t0)
                return fail("Camera refused PLAY (${play.code}).")
            }
            steps += Step("PLAY", true, "200 OK", now() - t0)

            t0 = now()
            val media = c.receiveMedia(channel, mediaWaitMs)
            val recvMs = now() - t0
            if (media.packets == 0) {
                steps += Step("Video data", false, "no packets in ${mediaWaitMs / 1000}s", recvMs)
                c.teardown(controlUrl, sessionHeader)
                return fail("Handshake OK but no video arrived. The camera may have reached its max number of streams, or the stream is disabled.")
            }
            kbps = if (media.durationMs > 0) (media.bytes * 8 / media.durationMs).toInt() else 0
            steps += Step("Video data", true,
                "${media.packets} packets, ${media.bytes / 1024} KB, ~$kbps kbit/s" +
                    (if (media.keyframe) ", keyframe seen" else "") +
                    (media.firstPacketMs?.let { ", first after $it ms" } ?: ""), recvMs)

            // 9. TEARDOWN
            t0 = now()
            val ok = c.teardown(controlUrl, sessionHeader)
            steps += Step("TEARDOWN", true, if (ok) "200 OK" else "no reply (fine)", now() - t0)
        } catch (e: SocketTimeoutException) {
            steps += Step(steps.lastOrNull()?.name?.let { "after $it" } ?: "RTSP", false, "timeout – camera stopped answering", 0)
            return fail("The camera stopped responding during the RTSP handshake.")
        } catch (e: Exception) {
            steps += Step("RTSP", false, "${e.javaClass.simpleName}: ${e.message}", 0)
            return fail("RTSP protocol error: ${e.message}")
        } finally {
            c.closeQuietly()
        }

        val summary = buildString {
            append("OK – ")
            append(codec ?: "video")
            if (width > 0) append(" ${width}x$height")
            if (fps > 0) append(" @${fmt(fps)}fps")
            if (kbps > 0) append(", ~$kbps kbit/s")
            append(", auth: ${c.authType ?: "none"}")
        }
        val hint = when {
            width in 1..1279 -> "Resolution is low for plate reading – use the main stream (not sub-stream)."
            else -> null
        }
        return Result(true, summary, hint, steps, codec, width, height, fps, c.authType, kbps, System.currentTimeMillis() - started)
    }

    // ------------------------------------------------------------------ URL

    class Target(
        val secure: Boolean, val host: String, val port: Int, val pathQuery: String,
        val user: String?, val password: String?, val warning: String?
    ) {
        val requestUrl: String get() = "${if (secure) "rtsps" else "rtsp"}://$host:$port$pathQuery"
    }

    companion object {
        fun parseUrl(raw: String): Target {
            val u = raw.trim()
            val secure = when {
                u.startsWith("rtsp://", true) -> false
                u.startsWith("rtsps://", true) -> true
                else -> throw IllegalArgumentException("must start with rtsp://")
            }
            var rest = u.substringAfter("://")
            var user: String? = null
            var pass: String? = null
            var warning: String? = null
            val at = rest.lastIndexOf('@')
            if (at >= 0) {
                val userInfo = rest.substring(0, at)
                rest = rest.substring(at + 1)
                val special = userInfo.filter { it in "@#?/[] " }.toSet()
                if (special.isNotEmpty()) warning = "password contains ${special.joinToString(" ") { "'$it'" }} – the app encodes it automatically"
                val colon = userInfo.indexOf(':')
                user = percentDecode(if (colon >= 0) userInfo.substring(0, colon) else userInfo)
                pass = if (colon >= 0) percentDecode(userInfo.substring(colon + 1)) else ""
            }
            val end = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
            val hostPort = rest.substring(0, end)
            var pathQuery = rest.substring(end)
            if (pathQuery.isEmpty()) pathQuery = "/"
            if (pathQuery.startsWith("?")) pathQuery = "/$pathQuery"
            val host: String
            val port: Int
            if (hostPort.startsWith("[")) { // IPv6
                host = hostPort.substringAfter("[").substringBefore("]")
                port = hostPort.substringAfter("]:", "").toIntOrNull() ?: if (secure) 322 else 554
            } else {
                host = hostPort.substringBefore(':')
                port = if (hostPort.contains(':')) hostPort.substringAfter(':').toIntOrNull()
                    ?: throw IllegalArgumentException("invalid port") else if (secure) 322 else 554
            }
            if (host.isBlank()) throw IllegalArgumentException("missing host/IP")
            return Target(secure, host, port, pathQuery, user, pass, warning)
        }

        /**
         * Returns the URL with the username and password percent-encoded, exactly the way VLC
         * would interpret it. Android's URI parser treats '#', '?', '/' and extra '@' in a raw
         * password as URL syntax (e.g. "pass#12@192.168.1.101" becomes host "12"), so every URL
         * is normalised before it is handed to the video player. Already-encoded URLs are unchanged.
         */
        fun normalizeUrl(raw: String): String {
            val t = try { parseUrl(raw) } catch (e: Exception) { return raw.trim() }
            val scheme = if (t.secure) "rtsps" else "rtsp"
            val host = if (t.host.contains(':')) "[${t.host}]" else t.host
            val auth = if (t.user != null) "${percentEncode(t.user)}:${percentEncode(t.password ?: "")}@" else ""
            return "$scheme://$auth$host:${t.port}${t.pathQuery}"
        }

        private fun percentEncode(s: String): String {
            val sb = StringBuilder()
            for (byte in s.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                val ch = c.toChar()
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') sb.append(ch)
                else sb.append('%').append("%02X".format(c))
            }
            return sb.toString()
        }

        private fun percentDecode(s: String): String {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch == '%' && i + 2 < s.length && s.substring(i + 1, i + 3).toIntOrNull(16) != null) {
                    out.write(s.substring(i + 1, i + 3).toInt(16)); i += 3
                } else {
                    out.write(ch.toString().toByteArray(Charsets.UTF_8)); i++
                }
            }
            return out.toString("UTF-8")
        }

        fun resolveControl(base: String, control: String?): String = when {
            control == null || control == "*" -> base
            control.startsWith("rtsp://", true) || control.startsWith("rtsps://", true) -> control
            else -> base.trimEnd('/') + "/" + control.trimStart('/')
        }

        private fun now() = System.currentTimeMillis()
        private fun fmt(f: Float) = if (f % 1f == 0f) f.toInt().toString() else "%.1f".format(f)

        fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        /** Own Base64 so this works on Android 7 (java.util.Base64 needs API 26). */
        fun base64Encode(data: ByteArray): String {
            val sb = StringBuilder()
            var i = 0
            while (i < data.size) {
                val b0 = data[i].toInt() and 0xFF
                val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
                val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1
                sb.append(B64[b0 shr 2])
                sb.append(B64[((b0 and 3) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
                sb.append(if (b1 < 0) '=' else B64[((b1 and 15) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
                sb.append(if (b2 < 0) '=' else B64[b2 and 63])
                i += 3
            }
            return sb.toString()
        }

        fun base64Decode(s: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var buf = 0
            var bits = 0
            for (ch in s) {
                val v = B64.indexOf(ch)
                if (v < 0) continue // skips '=', whitespace
                buf = (buf shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.write((buf shr bits) and 0xFF)
                }
            }
            return out.toByteArray()
        }
    }

    // ------------------------------------------------------------------ RTSP connection

    private class Response(val code: Int, val reason: String, val headers: List<Pair<String, String>>, val body: ByteArray) {
        fun header(name: String) = headers.firstOrNull { it.first.equals(name, true) }?.second
        fun headers(name: String) = headers.filter { it.first.equals(name, true) }.map { it.second }
        fun bodyText() = String(body, Charsets.UTF_8)
    }

    private class Media(val packets: Int, val bytes: Long, val durationMs: Long, val keyframe: Boolean, val firstPacketMs: Long?)

    private class Conn(val socket: Socket, val target: Target) {
        private val input: InputStream = BufferedInputStream(socket.getInputStream())
        private val output: OutputStream = socket.getOutputStream()
        private var cseq = 1
        private var challenge: Map<String, String>? = null
        private var digest = false
        var authType: String? = null

        fun request(method: String, uri: String, extra: List<Pair<String, String>> = emptyList()): Response {
            var resp = send(method, uri, extra)
            if (resp.code == 401 && target.user != null && challenge == null) {
                val auths = resp.headers("WWW-Authenticate")
                val dig = auths.firstOrNull { it.startsWith("Digest", true) }
                val basic = auths.firstOrNull { it.startsWith("Basic", true) }
                when {
                    dig != null -> { challenge = parseParams(dig.substring(6)); digest = true; authType = "Digest" }
                    basic != null -> { challenge = parseParams(basic.substring(5)); digest = false; authType = "Basic" }
                }
                if (challenge != null) resp = send(method, uri, extra)
            }
            return resp
        }

        private fun authorization(method: String, uri: String): String? {
            val ch = challenge ?: return null
            val user = target.user ?: return null
            val pass = target.password ?: ""
            if (!digest) return "Basic " + base64Encode("$user:$pass".toByteArray(Charsets.UTF_8))
            val realm = ch["realm"] ?: ""
            val nonce = ch["nonce"] ?: ""
            val ha1 = md5("$user:$realm:$pass")
            val ha2 = md5("$method:$uri")
            val qop = ch["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
            val sb = StringBuilder("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\"")
            if (qop != null) {
                val nc = "00000001"
                val cnonce = md5(System.nanoTime().toString()).take(16)
                sb.append(", response=\"${md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")}\", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
            } else {
                sb.append(", response=\"${md5("$ha1:$nonce:$ha2")}\"")
            }
            ch["opaque"]?.let { sb.append(", opaque=\"$it\"") }
            ch["algorithm"]?.let { sb.append(", algorithm=$it") }
            return sb.toString()
        }

        private fun send(method: String, uri: String, extra: List<Pair<String, String>>): Response {
            val sb = StringBuilder()
            sb.append("$method $uri RTSP/1.0\r\n")
            sb.append("CSeq: ${cseq++}\r\n")
            sb.append("User-Agent: VehicleGateANPR-Probe/1.0\r\n")
            authorization(method, uri)?.let { sb.append("Authorization: $it\r\n") }
            for ((k, v) in extra) sb.append("$k: $v\r\n")
            sb.append("\r\n")
            output.write(sb.toString().toByteArray(Charsets.UTF_8))
            output.flush()
            return readResponse()
        }

        /** Reads the next RTSP response, skipping any interleaved RTP data and server requests. */
        private fun readResponse(): Response {
            while (true) {
                val first = readByte()
                if (first == '$'.code) { skipInterleaved(); continue }
                val line = (first.toChar() + readLine()).trim()
                if (line.isEmpty()) continue
                val headers = readHeaders()
                val len = headers.firstOrNull { it.first.equals("Content-Length", true) }?.second?.trim()?.toIntOrNull() ?: 0
                val body = readExactly(len)
                if (!line.startsWith("RTSP/")) continue // a request from the server (e.g. GET_PARAMETER) – ignore
                val parts = line.split(' ', limit = 3)
                val code = parts.getOrNull(1)?.toIntOrNull() ?: throw IllegalStateException("bad status line: $line")
                return Response(code, parts.getOrNull(2) ?: "", headers, body)
            }
        }

        fun receiveMedia(channel: Int, waitMs: Long): Media {
            val start = System.currentTimeMillis()
            var packets = 0
            var bytes = 0L
            var keyframe = false
            var firstAt: Long? = null
            socket.soTimeout = 500
            while (System.currentTimeMillis() - start < waitMs) {
                val b = try { readByte() } catch (e: SocketTimeoutException) { continue }
                if (b == '$'.code) {
                    val ch = readByte()
                    val len = (readByte() shl 8) or readByte()
                    val data = readExactly(len)
                    if (ch == channel) {
                        if (firstAt == null) firstAt = System.currentTimeMillis() - start
                        packets++
                        bytes += len
                        if (!keyframe && isKeyframe(data)) keyframe = true
                    }
                } else if (b == 'R'.code || b == 'G'.code || b == 'S'.code || b == 'A'.code) {
                    readLine(); val h = readHeaders()
                    val len = h.firstOrNull { it.first.equals("Content-Length", true) }?.second?.trim()?.toIntOrNull() ?: 0
                    readExactly(len)
                }
            }
            socket.soTimeout = 3_000
            val dur = System.currentTimeMillis() - start - (firstAt ?: 0)
            return Media(packets, bytes, dur.coerceAtLeast(1), keyframe, firstAt)
        }

        /** H.264 IDR/SPS or H.265 IRAP/VPS/SPS inside an RTP packet (incl. FU-A / FU fragments). */
        private fun isKeyframe(rtp: ByteArray): Boolean {
            if (rtp.size < 14) return false
            val cc = rtp[0].toInt() and 0x0F
            val ext = (rtp[0].toInt() shr 4) and 1
            var off = 12 + cc * 4
            if (ext == 1 && off + 4 <= rtp.size) off += 4 + (((rtp[off + 2].toInt() and 0xFF) shl 8) or (rtp[off + 3].toInt() and 0xFF)) * 4
            if (off + 2 >= rtp.size) return false
            val n0 = rtp[off].toInt() and 0xFF
            val h264 = n0 and 0x1F
            if (h264 == 5 || h264 == 7) return true
            if (h264 == 28 || h264 == 24) { // FU-A / STAP-A
                val inner = rtp[off + 1].toInt() and 0x1F
                if (inner == 5 || inner == 7) return true
            }
            val h265 = (n0 shr 1) and 0x3F
            if (h265 in 16..21 || h265 == 32 || h265 == 33) return true
            if (h265 == 49 && off + 2 < rtp.size) {
                val inner = rtp[off + 2].toInt() and 0x3F
                if (inner in 16..21) return true
            }
            return false
        }

        fun teardown(uri: String, session: List<Pair<String, String>>): Boolean = try {
            socket.soTimeout = 2_000
            send("TEARDOWN", uri, session).code == 200
        } catch (e: Exception) {
            false
        }

        private fun skipInterleaved() {
            readByte()
            val len = (readByte() shl 8) or readByte()
            readExactly(len)
        }

        private fun readHeaders(): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            while (true) {
                val l = readLine()
                if (l.isEmpty()) return out
                val idx = l.indexOf(':')
                if (idx > 0) out += l.substring(0, idx).trim() to l.substring(idx + 1).trim()
            }
        }

        private fun readByte(): Int {
            val b = input.read()
            if (b < 0) throw EOFException("camera closed the connection")
            return b
        }

        private fun readLine(): String {
            val sb = StringBuilder()
            while (true) {
                val b = readByte()
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
                if (sb.length > 8192) throw IllegalStateException("line too long")
            }
            return sb.toString()
        }

        private fun readExactly(n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) throw EOFException("camera closed the connection")
                off += r
            }
            return buf
        }

        fun closeQuietly() { try { socket.close() } catch (_: Exception) {} }

        companion object {
            fun parseParams(s: String): Map<String, String> {
                val out = HashMap<String, String>()
                Regex("(\\w+)\\s*=\\s*(\"([^\"]*)\"|[^,\\s]+)").findAll(s).forEach {
                    out[it.groupValues[1].lowercase()] = it.groupValues[3].ifEmpty { it.groupValues[2].trim('"') }
                }
                return out
            }
        }
    }

    // ------------------------------------------------------------------ SDP

    class VideoTrack(val codec: String, val control: String?, val width: Int, val height: Int, val fps: Float)

    class Sdp(val video: VideoTrack?, val audioCodec: String?, val sessionControl: String?) {
        companion object {
            fun parse(text: String): Sdp {
                val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                var sessionControl: String? = null
                val sections = ArrayList<MutableList<String>>()
                for (l in lines) {
                    if (l.startsWith("m=")) sections += mutableListOf(l)
                    else if (sections.isEmpty()) { if (l.startsWith("a=control:")) sessionControl = l.substringAfter("a=control:") }
                    else sections.last() += l
                }
                var video: VideoTrack? = null
                var audio: String? = null
                for (sec in sections) {
                    val m = sec[0]
                    val pt = m.split(' ').getOrNull(3)
                    val rtpmap = sec.firstOrNull { it.startsWith("a=rtpmap:$pt ") }?.substringAfter(' ')
                    val codec = rtpmap?.substringBefore('/')?.uppercase() ?: when (pt) { "26" -> "JPEG"; "0" -> "PCMU"; "8" -> "PCMA"; else -> "PT$pt" }
                    val control = sec.firstOrNull { it.startsWith("a=control:") }?.substringAfter("a=control:")
                    if (m.startsWith("m=video") && video == null) {
                        var w = 0; var h = 0
                        sec.firstOrNull { it.startsWith("a=x-dimensions:") }?.substringAfter(':')?.split(',')?.let {
                            w = it.getOrNull(0)?.trim()?.toIntOrNull() ?: 0; h = it.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                        }
                        if (w == 0 && codec == "H264") {
                            val sprop = sec.firstOrNull { it.startsWith("a=fmtp:") }
                                ?.let { Regex("sprop-parameter-sets=([^;,\\s]+)").find(it)?.groupValues?.get(1) }
                            if (sprop != null) {
                                try { H264Sps.parse(base64Decode(sprop))?.let { (sw, sh) -> w = sw; h = sh } } catch (_: Exception) {}
                            }
                        }
                        val fps = sec.firstOrNull { it.startsWith("a=framerate:") }?.substringAfter(':')?.trim()?.toFloatOrNull() ?: 0f
                        video = VideoTrack(if (codec == "HEVC") "H265" else codec, control, w, h, fps)
                    } else if (m.startsWith("m=audio") && audio == null) {
                        audio = codec
                    }
                }
                return Sdp(video, audio, sessionControl)
            }
        }
    }

    /** Minimal H.264 SPS parser → coded picture size (after cropping). */
    object H264Sps {
        fun parse(nal: ByteArray): Pair<Int, Int>? {
            if (nal.isEmpty() || (nal[0].toInt() and 0x1F) != 7) return null
            // strip emulation-prevention bytes
            val rbsp = java.io.ByteArrayOutputStream()
            var zeros = 0
            for (i in 1 until nal.size) {
                val b = nal[i].toInt() and 0xFF
                if (zeros >= 2 && b == 3) { zeros = 0; continue }
                rbsp.write(b)
                zeros = if (b == 0) zeros + 1 else 0
            }
            val r = Bits(rbsp.toByteArray())
            val profile = r.u(8); r.u(8); r.u(8); r.ue()
            var chroma = 1
            if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                chroma = r.ue()
                if (chroma == 3) r.u(1)
                r.ue(); r.ue(); r.u(1)
                if (r.u(1) == 1) {
                    for (i in 0 until if (chroma != 3) 8 else 12) {
                        if (r.u(1) == 1) {
                            val size = if (i < 6) 16 else 64
                            var last = 8; var next = 8
                            for (j in 0 until size) {
                                if (next != 0) next = (last + r.se() + 256) % 256
                                last = if (next == 0) last else next
                            }
                        }
                    }
                }
            }
            r.ue() // log2_max_frame_num
            when (r.ue()) { // pic_order_cnt_type
                0 -> r.ue()
                1 -> { r.u(1); r.se(); r.se(); repeat(r.ue()) { r.se() } }
            }
            r.ue(); r.u(1)
            val wMbs = r.ue() + 1
            val hMap = r.ue() + 1
            val frameMbsOnly = r.u(1)
            if (frameMbsOnly == 0) r.u(1)
            r.u(1)
            var cl = 0; var cr = 0; var ct = 0; var cb = 0
            if (r.u(1) == 1) { cl = r.ue(); cr = r.ue(); ct = r.ue(); cb = r.ue() }
            val cropX = if (chroma == 0 || chroma == 3) 1 else 2
            val cropY = (if (chroma == 1) 2 else 1) * (2 - frameMbsOnly)
            val w = wMbs * 16 - (cl + cr) * cropX
            val h = (2 - frameMbsOnly) * hMap * 16 - (ct + cb) * cropY
            return if (w > 0 && h > 0) w to h else null
        }

        private class Bits(val d: ByteArray) {
            var pos = 0
            fun u(n: Int): Int {
                var v = 0
                repeat(n) {
                    val byte = d.getOrElse(pos shr 3) { throw IllegalStateException("SPS too short") }.toInt()
                    v = (v shl 1) or ((byte shr (7 - (pos and 7))) and 1)
                    pos++
                }
                return v
            }
            fun ue(): Int {
                var zeros = 0
                while (u(1) == 0) { zeros++; if (zeros > 31) throw IllegalStateException("bad ue") }
                return (1 shl zeros) - 1 + u(zeros)
            }
            fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) }
        }
    }
}
