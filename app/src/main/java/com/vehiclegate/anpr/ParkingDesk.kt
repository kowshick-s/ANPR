package com.vehiclegate.anpr

import com.vehiclegate.anpr.core.CaptureLogic
import com.vehiclegate.anpr.core.IndianPlateParser
import com.vehiclegate.anpr.core.ParkingFee
import com.vehiclegate.anpr.core.Reading
import com.vehiclegate.anpr.core.levenshtein
import com.vehiclegate.anpr.data.AppSettings
import com.vehiclegate.anpr.data.Capture
import com.vehiclegate.anpr.data.CaptureStatus
import com.vehiclegate.anpr.data.EntryStatus
import com.vehiclegate.anpr.data.MonthlyMember
import com.vehiclegate.anpr.data.PlateFix
import com.vehiclegate.anpr.data.VehicleEntry
import java.io.File
import kotlin.math.max

/**
 * Parking rules.
 *
 * Camera reads:
 *  - monthly user (matches the master list) → checked in / checked out automatically
 *  - everyone else → goes to the CAPTURED waiting list. Different readings of the same vehicle
 *    (same plate read several ways while it stands at the gate) are merged into one card.
 *
 * Staff then check a card in (picking or correcting the number, typing token/name), ignore it,
 * or – when it is a vehicle that is already parked – check it out. Manual check-in works the same
 * way without a capture.
 */
class ParkingDesk(private val app: AnprApp) {

    sealed class Result {
        /** Monthly user checked in automatically */
        class CheckedIn(val entry: VehicleEntry) : Result()
        /** Monthly user checked out automatically */
        class AutoCheckedOut(val entry: VehicleEntry) : Result()
        /** Added to (or merged into) the waiting list */
        class Captured(val capture: Capture, val isNew: Boolean) : Result()
        class Ignored(val plate: String, val reason: String) : Result()
    }

    /** What the counter knows about a number while staff type it. */
    sealed class Hint {
        object None : Hint()
        class Monthly(val member: MonthlyMember, val exact: Boolean) : Hint()
        class Parked(val entry: VehicleEntry, val amount: Int) : Hint()
        class Regular(val name: String, val model: String) : Hint()
    }

    private val dao get() = app.db.entries()
    private val cdao get() = app.db.captures()

    private fun same(a: String, b: String) = a == b || (a.length >= 8 && b.length >= 8 && levenshtein(a, b) <= 1)

    /** Compact + display form of a typed number (validated when possible), or null if too short. */
    fun normalize(typed: String): Pair<String, String>? {
        val clean = typed.uppercase().filter { it.isLetterOrDigit() }
        if (clean.length < 4) return null
        val p = IndianPlateParser.parse(clean)
        return (p?.compact ?: clean) to (p?.formatted ?: clean)
    }

    private fun formatted(compact: String) = IndianPlateParser.parse(compact)?.formatted ?: compact

    private suspend fun validMembers(at: Long) =
        app.db.members().all().filter { it.validTill == null || at <= it.validTill }

    /** Monthly user for [plate]: exact, or one character off when only one member is that close. */
    suspend fun monthlyMemberFor(plate: String, at: Long, allowNear: Boolean = true): MonthlyMember? {
        val members = validMembers(at)
        members.firstOrNull { it.plate == plate }?.let { return it }
        if (!allowNear) return null
        return members.filter { same(it.plate, plate) }.singleOrNull()
    }

    // ------------------------------------------------------------------ camera reads

    /** [read] is a vehicle the cameras decided on (entryTime = first seen). */
    suspend fun onDetected(read: VehicleEntry, s: AppSettings): Result {
        val fix = app.db.fixes().get(read.plate)
        val d = if (fix != null) read.copy(plate = fix.correct, plateFormatted = fix.correctFormatted) else read
        expireStale(d.entryTime)

        val member = monthlyMemberFor(d.plate, d.entryTime)
        if (member != null) {
            return monthly(d.copy(plate = member.plate, plateFormatted = formatted(member.plate)), member, s)
        }
        return addCapture(d)
    }

    /** Monthly user: check in, or (already in) check out. Seen again within the minimum stay = still at the gate. */
    private suspend fun monthly(d: VehicleEntry, member: MonthlyMember, s: AppSettings): Result {
        val t = d.entryTime
        val minStayMs = s.minStayMinutes * 60_000L
        val open = dao.openSessions().firstOrNull { it.plate == d.plate }
        if (open != null) {
            if (t - open.entryTime < minStayMs) {
                d.snapshotPath?.let { File(it).delete() }
                return Result.Ignored(open.plateFormatted, "monthly user still at the gate")
            }
            val out = open.copy(status = EntryStatus.OUT, exitDetectedAt = t, exitTime = t, amount = 0, monthly = true)
            dao.update(out)
            d.snapshotPath?.let { File(it).delete() }
            return Result.AutoCheckedOut(out)
        }
        val recentlyOut = dao.closedSince(t - minStayMs).firstOrNull { it.plate == d.plate }
        if (recentlyOut != null) {
            d.snapshotPath?.let { File(it).delete() }
            return Result.Ignored(recentlyOut.plateFormatted, "monthly user just left")
        }
        val (_, model) = previousDetails(d.plate)
        val entry = d.copy(status = EntryStatus.IN, name = member.name.ifBlank { previousDetails(d.plate).first },
            token = "", model = model, monthly = true)
        return Result.CheckedIn(entry.copy(id = dao.insert(entry)))
    }

    /** Daily vehicle: merge into a waiting card of the same vehicle, or start a new card. */
    private suspend fun addCapture(d: VehicleEntry): Result {
        val r = Reading(d.plate, d.plateFormatted, d.confidence * max(1, d.totalReads), max(1, d.totalReads))
        val match = cdao.pendingSince(d.entryTime - MERGE_WINDOW_MS).firstOrNull { c ->
            CaptureLogic.decode(c.readings).any { CaptureLogic.similar(it.compact, d.plate) }
        }
        if (match == null) {
            val c = Capture(
                plate = d.plate, plateFormatted = d.plateFormatted,
                firstSeen = d.entryTime, lastSeen = d.lastSeenTime, reads = r.count,
                readings = CaptureLogic.encode(listOf(r)), snapshotPath = d.snapshotPath,
                confidence = d.confidence, cameraSummary = d.cameraSummary
            )
            return Result.Captured(c.copy(id = cdao.insert(c)), true)
        }
        val readings = CaptureLogic.merge(CaptureLogic.decode(match.readings), r)
        val best = readings.first()
        val betterPicture = d.snapshotPath != null && (match.snapshotPath == null || d.confidence > match.confidence)
        if (betterPicture) match.snapshotPath?.let { File(it).delete() } else d.snapshotPath?.let { File(it).delete() }
        val c = match.copy(
            plate = best.compact, plateFormatted = best.formatted,
            lastSeen = max(match.lastSeen, d.lastSeenTime), reads = match.reads + r.count,
            readings = CaptureLogic.encode(readings),
            snapshotPath = if (betterPicture) d.snapshotPath else match.snapshotPath,
            confidence = max(match.confidence, d.confidence),
            cameraSummary = if (betterPicture) d.cameraSummary else match.cameraSummary
        )
        cdao.update(c)
        return Result.Captured(c, false)
    }

    /** Cards nobody handled for [EXPIRE_MS] disappear (pictures deleted). */
    private suspend fun expireStale(now: Long) {
        for (c in cdao.stale(now - EXPIRE_MS)) {
            c.snapshotPath?.let { File(it).delete() }
            cdao.update(c.copy(status = CaptureStatus.EXPIRED, snapshotPath = null))
        }
    }

    // ------------------------------------------------------------------ counter actions

    /** Live lookup while staff type a number. */
    suspend fun hint(typed: String, s: AppSettings): Hint {
        val (compact, _) = normalize(typed) ?: return Hint.None
        val now = System.currentTimeMillis()
        dao.openSessions().firstOrNull { same(it.plate, compact) }?.let { return Hint.Parked(it, amountDue(it, s)) }
        monthlyMemberFor(compact, now)?.let { return Hint.Monthly(it, it.plate == compact) }
        val (name, model) = previousDetails(compact)
        if (name.isNotBlank() || model.isNotBlank()) return Hint.Regular(name, model)
        return Hint.None
    }

    /**
     * Staff check-in, from a capture card ([c]) or manual ([c] = null). The check-in time is the
     * moment the camera first saw the vehicle (manual: now). Returns the entry, or an error text.
     */
    suspend fun checkIn(c: Capture?, typed: String, who: String, model: String): Pair<VehicleEntry?, String?> {
        val (compact, formatted) = normalize(typed) ?: return null to "Enter the full vehicle number"
        val open = dao.openSessions().firstOrNull { same(it.plate, compact) }
        if (open != null) return null to "${open.plateFormatted} is already parked – check it out instead"
        val now = System.currentTimeMillis()
        val member = monthlyMemberFor(compact, now)
        val (name0, model0) = previousDetails(compact)
        val entry = VehicleEntry(
            plate = member?.plate ?: compact,
            plateFormatted = if (member != null) formatted(member.plate) else formatted,
            entryTime = c?.firstSeen ?: now,
            lastSeenTime = c?.lastSeen ?: now,
            confidence = c?.confidence ?: 1f,
            totalReads = c?.reads ?: 0,
            cameraCount = if (c != null) 1 else 0,
            cameraSummary = c?.cameraSummary ?: MANUAL,
            snapshotPath = c?.snapshotPath,
            status = EntryStatus.IN,
            name = who.trim().ifBlank { member?.name?.takeIf { it.isNotBlank() } ?: name0 },
            token = "",
            model = model.trim().ifBlank { model0 },
            monthly = member != null
        )
        val saved = entry.copy(id = dao.insert(entry))
        if (c != null) {
            cdao.update(c.copy(status = CaptureStatus.DONE))
            rememberCorrections(c, saved.plate, saved.plateFormatted)
        }
        return saved to null
    }

    /** Wrong readings on the card are fixed automatically next time. */
    private suspend fun rememberCorrections(c: Capture, compact: String, formatted: String) {
        for (r in CaptureLogic.decode(c.readings)) {
            if (r.compact != compact && CaptureLogic.similar(r.compact, compact)) {
                app.db.fixes().upsert(PlateFix(r.compact, compact, formatted))
            }
        }
        app.db.fixes().delete(compact)
    }

    /** Card is junk / not a vehicle to check in. */
    suspend fun ignore(c: Capture) {
        c.snapshotPath?.let { File(it).delete() }
        cdao.update(c.copy(status = CaptureStatus.IGNORED, snapshotPath = null))
    }

    /** A card that is a parked vehicle leaving: check that vehicle out and close the card. */
    suspend fun checkOutFromCapture(c: Capture, open: VehicleEntry, s: AppSettings): VehicleEntry {
        val out = checkOut(dao.get(open.id) ?: open, s)
        ignore(c)
        return out
    }

    /** Staff check-out (money collected). */
    suspend fun checkOut(e: VehicleEntry, s: AppSettings): VehicleEntry {
        val exit = System.currentTimeMillis()
        val amount = if (e.isMonthly) 0 else ParkingFee.amount(e.entryTime, exit, s.feeAmount, s.feePeriodHours)
        val out = e.copy(status = EntryStatus.OUT, exitTime = exit, exitDetectedAt = e.exitDetectedAt ?: exit, amount = amount)
        dao.update(out)
        return out
    }

    /** Checked out by mistake: back to parked. */
    suspend fun undoCheckOut(e: VehicleEntry) =
        dao.update(e.copy(status = EntryStatus.IN, exitTime = null, exitDetectedAt = null, amount = null))

    /** Last Token/Name and model typed for this plate (or for a plate one character different). */
    suspend fun previousDetails(plate: String): Pair<String, String> {
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
     * Staff corrected the number of a parked vehicle. The correction is remembered, so the same
     * wrong read is fixed automatically next time. Returns an error message, or null when saved.
     */
    suspend fun correctPlate(e: VehicleEntry, typed: String): String? {
        val (compact, formatted) = normalize(typed) ?: return "Enter the full vehicle number"
        if (compact == e.plate) return null
        val cur = dao.get(e.id) ?: e
        val member = monthlyMemberFor(compact, cur.entryTime, allowNear = false)
        val (name, model) = previousDetails(compact)
        dao.update(cur.copy(
            plate = compact, plateFormatted = formatted,
            name = cur.name?.takeIf { it.isNotBlank() } ?: member?.name?.takeIf { it.isNotBlank() } ?: name,
            model = cur.model?.takeIf { it.isNotBlank() } ?: model,
            monthly = member != null || cur.isMonthly
        ))
        app.db.fixes().upsert(PlateFix(e.plate, compact, formatted))
        app.db.fixes().delete(compact)
        return null
    }

    /**
     * Saves token/name and model typed on the counter. Returns the other parked vehicle that already
     * uses the same token number, if any (to warn staff).
     */
    suspend fun saveDetails(e: VehicleEntry, who: String, model: String? = null): VehicleEntry? {
        val cur = dao.get(e.id) ?: e
        dao.update(cur.copy(name = who.trim(), model = model?.trim() ?: cur.model))
        return duplicateToken(who, e.id)
    }

    /** Another parked vehicle with the same token number (digits only). */
    suspend fun duplicateToken(who: String, exceptId: Long = -1): VehicleEntry? {
        val t = who.trim()
        if (t.isEmpty() || !t.all { it.isDigit() }) return null
        return dao.openSessions().firstOrNull { it.id != exceptId && it.name?.trim() == t }
    }

    /** Amount if the vehicle left now. */
    fun amountDue(e: VehicleEntry, s: AppSettings): Int =
        if (e.isMonthly) 0 else ParkingFee.amount(e.entryTime, System.currentTimeMillis(), s.feeAmount, s.feePeriodHours)

    /** The parked vehicle a capture card most likely is (any of its readings), if any. */
    fun parkedMatch(c: Capture, parked: List<VehicleEntry>): VehicleEntry? {
        val readings = CaptureLogic.decode(c.readings).map { it.compact }.ifEmpty { listOf(c.plate) }
        return readings.firstNotNullOfOrNull { r -> parked.firstOrNull { same(it.plate, r) } }
    }

    companion object {
        const val MANUAL = "Manual"
        /** Reads of the same vehicle this close in time are merged into one card. */
        private const val MERGE_WINDOW_MS = 3 * 60_000L
        /** Unhandled cards disappear after this. */
        private const val EXPIRE_MS = 45 * 60_000L
    }
}
