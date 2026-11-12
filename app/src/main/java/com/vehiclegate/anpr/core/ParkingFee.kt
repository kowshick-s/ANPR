package com.vehiclegate.anpr.core

import kotlin.math.max

/**
 * Parking fee: [rate] rupees for every started period of [periodHours] hours
 * (default ₹10 per 24 h – 2 h = ₹10, 24 h = ₹10, 25 h = ₹20). Minimum one period.
 */
object ParkingFee {
    fun amount(entryMs: Long, exitMs: Long, rate: Int, periodHours: Int): Int {
        val periodMs = max(1, periodHours) * 3_600_000L
        val duration = max(0L, exitMs - entryMs)
        val periods = max(1L, (duration + periodMs - 1) / periodMs)
        return (periods * rate).toInt()
    }

    /** "2h 05m", "1d 3h", "15m" */
    fun duration(entryMs: Long, exitMs: Long): String {
        val mins = max(0L, exitMs - entryMs) / 60_000
        val d = mins / (24 * 60)
        val h = (mins / 60) % 24
        val m = mins % 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h %02dm".format(m)
            else -> "${m}m"
        }
    }
}
