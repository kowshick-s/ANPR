package com.vehiclegate.anpr

import com.vehiclegate.anpr.core.ParkingFee
import com.vehiclegate.anpr.core.levenshtein
import com.vehiclegate.anpr.data.AppSettings
import com.vehiclegate.anpr.data.EntryStatus
import com.vehiclegate.anpr.data.MonthlyMember
import com.vehiclegate.anpr.data.VehicleEntry

/**
 * Parking rules applied to every detected vehicle:
 *  - not parked yet                    → CHECK-IN (name filled from the monthly list or history)
 *  - parked, seen again within min stay → ignored (still at the gate / lingering)
 *  - parked, seen again after min stay  → leaving: monthly = auto check-out, daily = CHECK-OUT list
 *  - just checked out, seen again soon  → ignored (still driving out)
 * Plates are matched allowing one misread character.
 */
class ParkingDesk(private val app: AnprApp) {

    sealed class Result {
        class CheckedIn(val entry: VehicleEntry) : Result()
        class Leaving(val entry: VehicleEntry) : Result()
        class AutoCheckedOut(val entry: VehicleEntry) : Result()
        class Ignored(val plate: String, val reason: String) : Result()
    }

    private val dao get() = app.db.entries()

    private fun same(a: String, b: String) = a == b || (a.length >= 8 && b.length >= 8 && levenshtein(a, b) <= 1)

    suspend fun monthlyMemberFor(plate: String, at: Long): MonthlyMember? =
        app.db.members().all().firstOrNull { same(it.plate, plate) && (it.validTill == null || at <= it.validTill) }

    /** [detected] is a new vehicle record from the cameras (status IN, entryTime = first seen). */
    suspend fun onDetected(detected: VehicleEntry, s: AppSettings): Result {
        val t = detected.entryTime
        val minStayMs = s.minStayMinutes * 60_000L
        val member = monthlyMemberFor(detected.plate, t)

        val open = dao.openSessions().firstOrNull { same(it.plate, detected.plate) }
        if (open != null) {
            if (open.state == EntryStatus.EXIT) {
                val seen = open.exitDetectedAt ?: open.entryTime
                if (t - seen < minStayMs) {
                    return Result.Ignored(open.plateFormatted, "already waiting for check-out")
                }
                // Never checked out at the desk, and now arriving again: close the old visit.
                dao.update(open.copy(status = EntryStatus.OUT, exitTime = seen))
            } else {
                if (t - open.entryTime < minStayMs) {
                    return Result.Ignored(open.plateFormatted, "already checked in at the gate")
                }
                return if (open.isMonthly || member != null) {
                    val out = open.copy(status = EntryStatus.OUT, exitDetectedAt = t, exitTime = t, amount = 0)
                    dao.update(out)
                    Result.AutoCheckedOut(out)
                } else {
                    val amount = ParkingFee.amount(open.entryTime, t, s.feeAmount, s.feePeriodHours)
                    val leaving = open.copy(status = EntryStatus.EXIT, exitDetectedAt = t, amount = amount)
                    dao.update(leaving)
                    Result.Leaving(leaving)
                }
            }
        } else {
            // Just checked out and still driving away?
            val recentlyOut = dao.closedSince(t - minStayMs).firstOrNull { same(it.plate, detected.plate) }
            if (recentlyOut != null) return Result.Ignored(recentlyOut.plateFormatted, "just checked out")
        }

        // Name and model come back automatically from earlier visits; the token is entered fresh.
        val name = member?.name?.takeIf { it.isNotBlank() } ?: dao.lastNameFor(detected.plate) ?: ""
        val model = dao.lastModelFor(detected.plate) ?: ""
        val entry = detected.copy(status = EntryStatus.IN, name = name, token = "", model = model, monthly = member != null)
        return Result.CheckedIn(entry.copy(id = dao.insert(entry)))
    }

    /** Staff confirmed check-out (money collected). Fee counts until the vehicle was seen leaving. */
    suspend fun checkOut(e: VehicleEntry, s: AppSettings): VehicleEntry {
        val exit = e.exitDetectedAt ?: System.currentTimeMillis()
        val amount = if (e.isMonthly) 0 else ParkingFee.amount(e.entryTime, exit, s.feeAmount, s.feePeriodHours)
        val out = e.copy(status = EntryStatus.OUT, exitTime = exit, exitDetectedAt = e.exitDetectedAt ?: exit, amount = amount)
        dao.update(out)
        return out
    }

    /** "Not leaving" – the exit detection was wrong; keep it parked. */
    suspend fun cancelExit(e: VehicleEntry) =
        dao.update(e.copy(status = EntryStatus.IN, exitDetectedAt = null, amount = null))

    /** Saves token / name / model typed on the dashboard (reads the row fresh so nothing is lost). */
    suspend fun saveDetails(e: VehicleEntry, token: String, name: String, model: String? = null) {
        val cur = dao.get(e.id) ?: e
        dao.update(cur.copy(token = token.trim(), name = name.trim(), model = model?.trim() ?: cur.model))
    }

    /** Amount if the vehicle left now (or when it was seen leaving). */
    fun amountDue(e: VehicleEntry, s: AppSettings): Int =
        if (e.isMonthly) 0 else ParkingFee.amount(e.entryTime, e.exitDetectedAt ?: System.currentTimeMillis(), s.feeAmount, s.feePeriodHours)
}
