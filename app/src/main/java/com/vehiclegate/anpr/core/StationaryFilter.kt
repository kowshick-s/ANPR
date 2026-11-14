package com.vehiclegate.anpr.core

/**
 * Stops re-reading a plate that stands at the same spot in a camera picture (a bike parked or
 * waiting at the gate). OCR of a standing plate produces a new wrong variant every few seconds.
 *
 * A "spot" is a plate position (centre, 0..1 of the search area) per camera. A plate seen at the
 * same spot without a break for longer than [maxStandMs] is ignored until it moves away.
 */
class StationaryFilter(
    private val maxStandMs: Long = 10_000,
    /** Gap after which the spot counts as left (a new vehicle may stop there). */
    private val breakMs: Long = 5_000,
    /** Max centre distance (fraction of the picture) for "same spot". */
    private val radius: Float = 0.05f
) {
    private class Spot(val camId: String, var x: Float, var y: Float, val since: Long, var last: Long)

    private val spots = ArrayList<Spot>()

    /** True = read it; false = this plate has been standing here too long. */
    @Synchronized
    fun accept(camId: String, x: Float, y: Float, now: Long): Boolean {
        spots.removeAll { now - it.last > breakMs }
        val s = spots.firstOrNull { it.camId == camId && dist(it.x, it.y, x, y) <= radius }
        if (s == null) { spots += Spot(camId, x, y, now, now); return true }
        s.last = now
        s.x = (s.x + x) / 2; s.y = (s.y + y) / 2
        return now - s.since <= maxStandMs
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx; val dy = ay - by
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
