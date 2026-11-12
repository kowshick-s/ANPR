package com.vehiclegate.anpr

import com.vehiclegate.anpr.core.IndianPlateParser
import com.vehiclegate.anpr.core.ParkingFee
import com.vehiclegate.anpr.core.levenshtein
import com.vehiclegate.anpr.data.AppSettings
import com.vehiclegate.anpr.data.EntryStatus
import com.vehiclegate.anpr.data.MonthlyMember
import com.vehiclegate.anpr.data.PlateFix
import com.vehiclegate.anpr.data.VehicleEntry

/**
 * Parking rules applied to every detected vehicle:
 *  - not parked yet                    → CHECK-IN (name filled from the monthly list or history)
 *  - parked, seen again                 → leaving: monthly = auto check-out, daily = CHECK-OUT list
 *  - not parked (incl. checked out)       → CHECK-IN again
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
    suspend fun onDetected(read: VehicleEntry, s: AppSettings): Result {
        // A plate staff corrected before is saved as the corrected number.
        val fix = app.db.fixes().get(read.plate)
        val detected = if (fix != null) read.copy(plate = fix.correct, plateFormatted = fix.correctFormatted) else read
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
                // Checked in and seen again = leaving. (The same pass past several cameras was
                // already merged into one read by the re-entry cooldown.)
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
        }
        // Not checked in now → check in (also right after a check-out: a new visit).
        // The same pass past several cameras is merged earlier, by the re-entry cooldown.

        // Token/Name and model come back automatically from earlier visits.
        val (name, model) = previousDetails(detected.plate)
        val entry = detected.copy(
            status = EntryStatus.IN,
            name = member?.name?.takeIf { it.isNotBlank() } ?: name,
            token = "", model = model, monthly = member != null
        )
        return Result.CheckedIn(entry.copy(id = dao.insert(entry)))
    }

    /** Last Token/Name and model typed for this plate (or for a plate one character different). */
    private suspend fun previousDetails(plate: String): Pair<String, String> {
        var name = dao.lastNameFor(plate)
        var model = dao.lastModelFor(plate)
        if (name == null || model == null) {
            val near = dao.recentWithDetails().filter { same(it.plate, plate) }
            if (name == null) name = near.firstOrNull { !it.name.isNullOrBlank() }?.name
            if (model == null) model = near.firstOrNull { !it.model.isNullOrBlank() }?.model
        }
        return (name ?: "") to (model ?: "")
    }

    /**
     * Staff corrected a wrongly read plate. Saves the new number on this visit and remembers the
     * correction, so the next time the cameras read the same wrong number it is fixed automatically.
     * Returns an error message, or null when saved.
     */
    suspend fun correctPlate(e: VehicleEntry, typed: String): String? {
        val clean = typed.uppercase().filter { it.isLetterOrDigit() }
        if (clean.length < 4) return "Enter the full vehicle number"
        val parsed = IndianPlateParser.parse(clean)
        val compact = parsed?.compact ?: clean
        val formatted = parsed?.formatted ?: clean
        if (compact == e.plate) return null
        val cur = dao.get(e.id) ?: e
        val member = monthlyMemberFor(compact, cur.entryTime)
        val (name, model) = previousDetails(compact)
        dao.update(cur.copy(
            plate = compact, plateFormatted = formatted,
            name = cur.name?.takeIf { it.isNotBlank() } ?: member?.name?.takeIf { it.isNotBlank() } ?: name,
            model = cur.model?.takeIf { it.isNotBlank() } ?: model,
            monthly = member != null || cur.isMonthly
        ))
        app.db.fixes().upsert(PlateFix(e.plate, compact, formatted))
        app.db.fixes().delete(compact) // never "correct" a right number into something else
        return null
    }

    /** Staff confirmed check-out (money collected). Fee counts until the vehicle was seen leaving. */
    suspend fun checkOut(e: VehicleEntry, s: AppSettings): VehicleEntry {
        val exit = e.exitDetectedAt ?: System.currentTimeMillis()
        val amount = if (e.isMonthly) 0 else ParkingFee.amount(e.entryTime, exit, s.feeAmount, s.feePeriodHours)
        val out = e.copy(status = EntryStatus.OUT, exitTime = exit, exitDetectedAt = e.exitDetectedAt ?: exit, amount = amount)
        dao.update(out)
        return out
    }

    /** Staff pressed Out on a parked vehicle: monthly = checked out, daily = moves to CHECK-OUT. */
    suspend fun markLeaving(e: VehicleEntry, s: AppSettings) {
        val cur = dao.get(e.id) ?: e
        val t = System.currentTimeMillis()
        if (cur.isMonthly) dao.update(cur.copy(status = EntryStatus.OUT, exitDetectedAt = t, exitTime = t, amount = 0))
        else dao.update(cur.copy(status = EntryStatus.EXIT, exitDetectedAt = t,
            amount = ParkingFee.amount(cur.entryTime, t, s.feeAmount, s.feePeriodHours)))
    }

    /** "Not leaving" – the exit detection was wrong; keep it parked. */
    suspend fun cancelExit(e: VehicleEntry) =
        dao.update(e.copy(status = EntryStatus.IN, exitDetectedAt = null, amount = null))

    /** Saves token / name / model typed on the dashboard (reads the row fresh so nothing is lost). */
    suspend fun saveDetails(e: VehicleEntry, who: String, model: String? = null) {
        val cur = dao.get(e.id) ?: e
        dao.update(cur.copy(name = who.trim(), model = model?.trim() ?: cur.model))
    }

    /** Amount if the vehicle left now (or when it was seen leaving). */
    fun amountDue(e: VehicleEntry, s: AppSettings): Int =
        if (e.isMonthly) 0 else ParkingFee.amount(e.entryTime, e.exitDetectedAt ?: System.currentTimeMillis(), s.feeAmount, s.feePeriodHours)
}
