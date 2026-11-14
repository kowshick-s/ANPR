package com.vehiclegate.anpr.core

/**
 * One OCR reading of a plate from one camera at one moment.
 * [S] is the snapshot type (android.graphics.Bitmap in the app, anything in tests).
 */
data class PlateRead<S>(
    val cameraId: String,
    val cameraName: String,
    val plate: ParsedPlate,
    /** Raw OCR confidence 0..1 from the text recogniser. */
    val ocrConfidence: Float,
    val timestampMs: Long,
    val snapshot: S? = null,
    /** Plate centre in the camera's search area, 0..1 (−1 = unknown) */
    val posX: Float = -1f,
    val posY: Float = -1f
) {
    /** Combined quality of this read: OCR confidence × how well it fitted the plate format. */
    val score: Float get() = (ocrConfidence * plate.formatScore).coerceIn(0f, 1f)
    val compact: String get() = plate.compact
}

/** What one camera contributed to a vehicle decision (shown on the TV for transparency). */
data class CameraVote(
    val cameraId: String,
    val cameraName: String,
    val bestText: String,
    val bestScore: Float,
    val reads: Int,
    val agreesWithFinal: Boolean
)

/** Final, fused decision for one vehicle. */
data class FusedVehicle<S>(
    val compact: String,
    val formatted: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val confidence: Float,
    val totalReads: Int,
    val agreeingReads: Int,
    val cameraVotes: List<CameraVote>,
    val snapshot: S?,
    /** Camera and plate position of the snapshot read (for grouping on the waiting list) */
    val cameraId: String? = null,
    val posX: Float = -1f,
    val posY: Float = -1f
)

class FusionParams(
    /** A vehicle track is closed after this long without a new matching read. */
    var settleMs: Long = 2_500,
    /** Hard cap on how long one track may stay open. */
    var maxTrackMs: Long = 15_000,
    /** Minimum number of reads (from any cameras) before a vehicle is accepted… */
    var minReads: Int = 2,
    /** …unless a single read is at least this good. */
    var singleReadMinScore: Float = 0.85f,
    /** A single read is accepted if it fitted the plate format this well (≤1 correction)… */
    var cleanFormatScore: Float = 0.9f,
    /** …and its OCR score is at least this. */
    var cleanReadMinScore: Float = 0.3f,
    /** Fused confidence below this is discarded as noise. */
    var minConfidence: Float = 0.45f,
    /** Same plate seen again within this window is treated as the same visit (not a new entry). */
    var cooldownMs: Long = 120_000
)

/**
 * Groups reads from all cameras into per-vehicle "tracks" and, once a vehicle has
 * passed (no new reads for [FusionParams.settleMs]), picks the best plate by
 * confidence-weighted, character-by-character voting across every read from every camera.
 *
 * Thread-safe: [submit] may be called from OCR worker threads, [tick] from a timer.
 * Callbacks are invoked outside the internal lock.
 */
class PlateFusionEngine<S>(
    val params: FusionParams,
    private val onVehicle: (FusedVehicle<S>) -> Unit,
    private val onDiscarded: ((plate: String, reason: String) -> Unit)? = null
) {
    private class Track<S>(var firstSeen: Long, var lastSeen: Long) {
        val reads = ArrayList<PlateRead<S>>()          // snapshots stripped to save memory
        val bestPerCamera = HashMap<String, PlateRead<S>>() // keeps one snapshot per camera
    }

    private companion object {
        const val SAME_SPOT = 0.06f
        const val SAME_SPOT_MS = 4_000L
    }

    private val lock = Any()
    private val tracks = ArrayList<Track<S>>()
    private val recentCommits = HashMap<String, Long>() // compact plate → last seen time

    fun submit(read: PlateRead<S>) {
        synchronized(lock) {
            // Vehicle already logged and still in view → just keep its cooldown alive.
            val committed = recentCommits.keys.firstOrNull { levenshtein(it, read.compact) <= 1 }
            if (committed != null && read.timestampMs - recentCommits.getValue(committed) < params.cooldownMs) {
                recentCommits[committed] = read.timestampMs
                return
            }

            val track = findTrack(read.compact) ?: findTrackAt(read) ?: Track<S>(read.timestampMs, read.timestampMs).also { tracks += it }
            track.reads += read.copy(snapshot = null)
            track.lastSeen = maxOf(track.lastSeen, read.timestampMs)
            track.firstSeen = minOf(track.firstSeen, read.timestampMs)
            val prev = track.bestPerCamera[read.cameraId]
            if (prev == null || read.score > prev.score) track.bestPerCamera[read.cameraId] = read
        }
    }

    /** Call periodically (e.g. every 500 ms). Closes settled tracks and emits vehicles. */
    fun tick(nowMs: Long) {
        val results = ArrayList<FusedVehicle<S>>()
        val discarded = ArrayList<Pair<String, String>>()
        synchronized(lock) {
            pruneCommits(nowMs)
            val it = tracks.iterator()
            while (it.hasNext()) {
                val t = it.next()
                if (nowMs - t.lastSeen >= params.settleMs || nowMs - t.firstSeen >= params.maxTrackMs) {
                    it.remove()
                    when (val r = finalize(t)) {
                        is Outcome.Accepted -> results += r.vehicle
                        is Outcome.Rejected -> discarded += r.plate to r.reason
                    }
                }
            }
        }
        results.forEach(onVehicle)
        discarded.forEach { (p, r) -> onDiscarded?.invoke(p, r) }
    }

    /** Close every open track immediately (e.g. on shutdown). */
    fun flush(nowMs: Long) = tick(nowMs + params.maxTrackMs + params.settleMs)

    fun reset() = synchronized(lock) { tracks.clear(); recentCommits.clear() }

    private fun pruneCommits(nowMs: Long) {
        recentCommits.entries.removeAll { nowMs - it.value > params.cooldownMs }
    }

    private fun findTrack(compact: String): Track<S>? {
        var best: Track<S>? = null
        var bestDist = Int.MAX_VALUE
        for (t in tracks) {
            val d = t.reads.minOf { levenshtein(it.compact, compact) }
            if (d < bestDist) { bestDist = d; best = t }
        }
        return best?.takeIf { bestDist <= matchThreshold(compact.length) }
    }

    /**
     * Same camera, plate at (almost) the same spot, moments ago → the same vehicle even if OCR read
     * the text quite differently (shifted / partial reads of a plate standing at the gate).
     */
    private fun findTrackAt(read: PlateRead<S>): Track<S>? {
        if (read.posX < 0) return null
        return tracks.firstOrNull { t ->
            t.reads.any {
                it.cameraId == read.cameraId && it.posX >= 0 &&
                    kotlin.math.abs(it.posX - read.posX) < SAME_SPOT && kotlin.math.abs(it.posY - read.posY) < SAME_SPOT &&
                    read.timestampMs - it.timestampMs in 0..SAME_SPOT_MS
            }
        }
    }

    private fun matchThreshold(len: Int) = if (len >= 9) 2 else 1

    private sealed class Outcome<out S> {
        class Accepted<S>(val vehicle: FusedVehicle<S>) : Outcome<S>()
        class Rejected(val plate: String, val reason: String) : Outcome<Nothing>()
    }

    private fun finalize(t: Track<S>): Outcome<S> {
        val reads = t.reads
        // 1. Most likely plate length (weighted by read quality).
        val modalLen = reads.groupBy { it.compact.length }
            .maxByOrNull { (_, rs) -> rs.sumOf { it.score.toDouble() } }!!.key
        val sameLen = reads.filter { it.compact.length == modalLen }

        // 2. Character-by-character weighted vote across all cameras.
        val votes = Array(modalLen) { HashMap<Char, Double>() }
        for (r in sameLen) r.compact.forEachIndexed { i, c ->
            votes[i].merge(c, r.score.toDouble().coerceAtLeast(0.01), Double::plus)
        }
        val consensus = String(CharArray(modalLen) { i -> votes[i].maxByOrNull { it.value }!!.key })

        // 3. Candidates = consensus + every distinct read. Choose the one best supported
        //    by the evidence that is still a valid plate.
        val candidates = linkedSetOf(consensus).apply { reads.forEach { add(it.compact) } }
        var final: ParsedPlate? = null
        var finalSupport = -1.0
        for (c in candidates) {
            val parsed = IndianPlateParser.parse(c) ?: continue
            var support = 0.0
            for (r in reads) {
                val d = levenshtein(r.compact, parsed.compact)
                support += when (d) { 0 -> r.score.toDouble(); 1 -> 0.5 * r.score; 2 -> 0.15 * r.score; else -> 0.0 }
            }
            if (support > finalSupport + 1e-9) { finalSupport = support; final = parsed }
        }
        val plate = final ?: return Outcome.Rejected(consensus, "no valid plate after fusion")

        // 4. Confidence = how much the reads agree × how good they were.
        val agreeing = reads.filter { it.compact == plate.compact }
        val posAgreement = if (plate.compact.length == modalLen) {
            votes.indices.map { i ->
                val total = votes[i].values.sum()
                if (total == 0.0) 0.0 else (votes[i][plate.compact[i]] ?: 0.0) / total
            }.average()
        } else 0.5
        val avgScore = sameLen.map { it.score.toDouble() }.average()
        val confidence = (0.5 * posAgreement + 0.5 * avgScore).toFloat().coerceIn(0f, 1f)
        val maxScore = reads.maxOf { it.score }

        // A fast vehicle may be read only once. Accept that if the read is clean: it needed at
        // most one character correction and its score is reasonable – or it is very confident.
        val bestRead = reads.maxByOrNull { it.score }!!
        val cleanSingle = bestRead.plate.formatScore >= params.cleanFormatScore && bestRead.score >= params.cleanReadMinScore
        if (reads.size < params.minReads && maxScore < params.singleReadMinScore && !cleanSingle)
            return Outcome.Rejected(plate.compact, "only ${reads.size} unclear read(s)")
        if (confidence < params.minConfidence)
            return Outcome.Rejected(plate.compact, "low confidence ${"%.2f".format(confidence)}")

        // 5. De-duplicate against recent entries (vehicle re-detected while still at the gate).
        val dup = recentCommits.entries.firstOrNull {
            levenshtein(it.key, plate.compact) <= 1 && t.firstSeen - it.value < params.cooldownMs
        }?.key
        if (dup != null) {
            recentCommits[dup] = t.lastSeen
            return Outcome.Rejected(plate.compact, "duplicate of $dup within cooldown")
        }
        recentCommits[plate.compact] = t.lastSeen

        // 6. Per-camera breakdown + best snapshot (prefer a camera that read the final plate).
        val votesByCam = reads.groupBy { it.cameraId }.map { (camId, rs) ->
            val best = rs.maxByOrNull { it.score }!!
            CameraVote(camId, best.cameraName, best.plate.formatted, best.score, rs.size,
                rs.any { it.compact == plate.compact })
        }.sortedByDescending { it.bestScore }
        val snapshotRead = t.bestPerCamera.values
            .sortedWith(compareByDescending<PlateRead<S>> { it.compact == plate.compact }.thenByDescending { it.score })
            .firstOrNull { it.snapshot != null }

        return Outcome.Accepted(
            FusedVehicle(
                compact = plate.compact,
                formatted = plate.formatted,
                firstSeenMs = t.firstSeen,
                lastSeenMs = t.lastSeen,
                confidence = confidence,
                totalReads = reads.size,
                agreeingReads = agreeing.size,
                cameraVotes = votesByCam,
                snapshot = snapshotRead?.snapshot,
                cameraId = snapshotRead?.cameraId,
                posX = snapshotRead?.posX ?: -1f,
                posY = snapshotRead?.posY ?: -1f
            )
        )
    }
}
