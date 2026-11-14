package com.vehiclegate.anpr.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Parking session states. */
object EntryStatus {
    /** Checked in, parked. */
    const val IN = "IN"
    /** Seen leaving – waiting for staff to collect and confirm check-out. */
    const val EXIT = "EXIT"
    /** Checked out. Entries from before the parking feature (status null) count as OUT. */
    const val OUT = "OUT"
}

/** One parking session: check-in (entry) and, later, check-out. */
@Entity(tableName = "vehicle_entries", indices = [Index("entryTime"), Index("plate")])
data class VehicleEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Compact plate, e.g. TN09AB1234 */
    val plate: String,
    /** Display plate, e.g. TN 09 AB 1234 */
    val plateFormatted: String,
    /** First time any camera saw the vehicle (= check-in time), epoch ms */
    val entryTime: Long,
    val lastSeenTime: Long,
    /** Fused confidence 0..1 */
    val confidence: Float,
    val totalReads: Int,
    val cameraCount: Int,
    /** Human-readable per-camera breakdown, one camera per line: "Gate Cam 1|TN 09 AB 1234|92|3|1" */
    val cameraSummary: String,
    val snapshotPath: String?,
    // ---- parking (added in DB version 2; nullable so old rows stay valid) ----
    /** [EntryStatus]; null = old entry, treated as OUT */
    val status: String? = EntryStatus.IN,
    /** When the vehicle was seen leaving (pending check-out) */
    val exitDetectedAt: Long? = null,
    /** Final check-out time */
    val exitTime: Long? = null,
    /** Amount due / collected in rupees (0 for monthly users) */
    val amount: Int? = null,
    /** Token number entered by staff */
    val token: String? = null,
    /** Customer name (entered by staff, or auto-filled from history / monthly list) */
    val name: String? = null,
    /** Vehicle is in the monthly users list */
    val monthly: Boolean? = false,
    /** Vehicle make/model typed by staff, e.g. "Activa 6G" (added in DB version 3) */
    val model: String? = null
) {
    val isMonthly get() = monthly == true
    val state get() = status ?: EntryStatus.OUT
}

/** Master data: vehicles that pay monthly (no daily fee, auto check-out). */
@Entity(tableName = "monthly_members")
data class MonthlyMember(
    /** Compact plate, e.g. TN09AB1234 */
    @PrimaryKey val plate: String,
    val name: String,
    val phone: String = "",
    /** Pass valid until (epoch ms), null = no end date */
    val validTill: Long? = null,
    val notes: String = ""
)

@Dao
interface VehicleEntryDao {
    @Query("SELECT * FROM vehicle_entries ORDER BY entryTime DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<VehicleEntry>>

    /** Latest check-in (for the big card on the dashboard). */
    @Query("SELECT * FROM vehicle_entries WHERE status IS NOT NULL ORDER BY entryTime DESC LIMIT 1")
    fun latestCheckIn(): Flow<VehicleEntry?>

    @Query("SELECT * FROM vehicle_entries WHERE status = 'IN' ORDER BY entryTime DESC")
    fun parked(): Flow<List<VehicleEntry>>

    @Query("SELECT * FROM vehicle_entries WHERE status = 'EXIT' ORDER BY exitDetectedAt DESC")
    fun leaving(): Flow<List<VehicleEntry>>

    /** Parked vehicles (also old "seen leaving" ones), newest first. */
    @Query("SELECT * FROM vehicle_entries WHERE status IN ('IN','EXIT') ORDER BY entryTime DESC")
    fun openFlow(): Flow<List<VehicleEntry>>

    /** Latest check-outs. */
    @Query("SELECT * FROM vehicle_entries WHERE status = 'OUT' AND exitTime IS NOT NULL ORDER BY exitTime DESC LIMIT :limit")
    fun recentOut(limit: Int): Flow<List<VehicleEntry>>

    /** Checked-out visits whose pictures are past the keep period. */
    @Query("SELECT * FROM vehicle_entries WHERE status = 'OUT' AND exitTime < :before AND snapshotPath IS NOT NULL")
    suspend fun picturesToDrop(before: Long): List<VehicleEntry>

    /** Every check-out (for search in the Checked out tab). */
    @Query("SELECT * FROM vehicle_entries WHERE status = 'OUT' AND exitTime IS NOT NULL ORDER BY exitTime DESC LIMIT 2000")
    suspend fun outForSearch(): List<VehicleEntry>

    /** Sessions not yet closed (parked or seen leaving). */
    @Query("SELECT * FROM vehicle_entries WHERE status IN ('IN','EXIT') ORDER BY entryTime DESC")
    suspend fun openSessions(): List<VehicleEntry>

    @Query("SELECT * FROM vehicle_entries WHERE status = 'OUT' AND exitTime >= :since")
    suspend fun closedSince(since: Long): List<VehicleEntry>

    @Query("SELECT name FROM vehicle_entries WHERE plate = :plate AND name IS NOT NULL AND name != '' ORDER BY entryTime DESC LIMIT 1")
    suspend fun lastNameFor(plate: String): String?

    /** Names/tokens typed for this plate, newest first (tokens are filtered out by the caller). */
    @Query("SELECT name FROM vehicle_entries WHERE plate = :plate AND name IS NOT NULL AND name != '' ORDER BY entryTime DESC LIMIT 30")
    suspend fun namesFor(plate: String): List<String>

    @Query("SELECT model FROM vehicle_entries WHERE plate = :plate AND model IS NOT NULL AND model != '' ORDER BY entryTime DESC LIMIT 1")
    suspend fun lastModelFor(plate: String): String?

    /** Recent visits that have a token/name or model typed – for matching a slightly misread plate. */
    @Query("SELECT * FROM vehicle_entries WHERE (name IS NOT NULL AND name != '') OR (model IS NOT NULL AND model != '') ORDER BY entryTime DESC LIMIT 3000")
    suspend fun recentWithDetails(): List<VehicleEntry>

    /** Every model typed so far (for the model suggestions). */
    @Query("SELECT DISTINCT model FROM vehicle_entries WHERE model IS NOT NULL AND model != '' ORDER BY model COLLATE NOCASE")
    suspend fun knownModels(): List<String>

    @Query("SELECT * FROM vehicle_entries WHERE id = :id")
    suspend fun get(id: Long): VehicleEntry?

    @Query("SELECT COUNT(*) FROM vehicle_entries WHERE entryTime >= :since")
    fun countSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM vehicle_entries WHERE status = 'OUT' AND exitTime >= :since")
    fun outCountSince(since: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM vehicle_entries WHERE status = 'OUT' AND exitTime >= :since")
    fun collectedSince(since: Long): Flow<Int>

    @Query("SELECT * FROM vehicle_entries ORDER BY entryTime DESC")
    suspend fun all(): List<VehicleEntry>

    @Insert
    suspend fun insert(entry: VehicleEntry): Long

    @Update
    suspend fun update(entry: VehicleEntry)

    @Delete
    suspend fun delete(entry: VehicleEntry)

    @Query("SELECT snapshotPath FROM vehicle_entries WHERE entryTime < :before")
    suspend fun snapshotsBefore(before: Long): List<String?>

    @Query("DELETE FROM vehicle_entries WHERE entryTime < :before")
    suspend fun deleteBefore(before: Long): Int

    @Query("DELETE FROM vehicle_entries")
    suspend fun clear()
}

@Dao
interface MonthlyMemberDao {
    @Query("SELECT * FROM monthly_members ORDER BY name COLLATE NOCASE")
    fun allFlow(): Flow<List<MonthlyMember>>

    @Query("SELECT * FROM monthly_members ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<MonthlyMember>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(m: MonthlyMember)

    @Query("DELETE FROM monthly_members WHERE plate = :plate")
    suspend fun delete(plate: String)
}

/** Capture card states. */
object CaptureStatus {
    const val PENDING = "PENDING"
    const val DONE = "DONE"
    const val IGNORED = "IGNORED"
    const val EXPIRED = "EXPIRED"
}

/**
 * Waiting list: a vehicle the cameras read, not yet checked in. Different OCR readings of the same
 * vehicle are merged into one card ([readings]); staff pick/correct the number and check it in.
 */
@Entity(tableName = "captures", indices = [Index("lastSeen")])
data class Capture(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Best reading so far (compact / display) */
    val plate: String,
    val plateFormatted: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val reads: Int,
    /** All readings, see CaptureLogic.encode */
    val readings: String,
    val snapshotPath: String?,
    val confidence: Float,
    val cameraSummary: String,
    val status: String = CaptureStatus.PENDING,
    /** Camera + plate position (0..1) of the last read – same spot = same vehicle (DB v6) */
    val camId: String? = null,
    val posX: Float? = null,
    val posY: Float? = null
)

@Dao
interface CaptureDao {
    @Query("SELECT * FROM captures WHERE status = 'PENDING' ORDER BY lastSeen DESC")
    fun pendingFlow(): Flow<List<Capture>>

    @Query("SELECT * FROM captures WHERE status = 'PENDING' AND lastSeen >= :since ORDER BY lastSeen DESC")
    suspend fun pendingSince(since: Long): List<Capture>

    @Query("SELECT * FROM captures WHERE id = :id")
    suspend fun get(id: Long): Capture?

    @Insert
    suspend fun insert(c: Capture): Long

    @Update
    suspend fun update(c: Capture)

    /** Pending cards nobody handled: expire them (and return their pictures for deleting). */
    @Query("SELECT * FROM captures WHERE status = 'PENDING' AND lastSeen < :before")
    suspend fun stale(before: Long): List<Capture>

    @Query("SELECT * FROM captures WHERE status = 'PENDING' AND id != :exceptId AND firstSeen BETWEEN :from AND :to")
    suspend fun pendingBetween(from: Long, to: Long, exceptId: Long): List<Capture>

    @Query("DELETE FROM captures WHERE lastSeen < :before AND status != 'PENDING'")
    suspend fun deleteBefore(before: Long)
}

/**
 * A plate number staff corrected by hand. When the cameras read [wrong] again, it is saved as
 * [correct] automatically (the same bike is often misread the same way every day).
 */
@Entity(tableName = "plate_fixes")
data class PlateFix(
    @PrimaryKey val wrong: String,
    val correct: String,
    val correctFormatted: String
)

@Dao
interface PlateFixDao {
    @Query("SELECT * FROM plate_fixes WHERE wrong = :plate")
    suspend fun get(plate: String): PlateFix?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(f: PlateFix)

    @Query("DELETE FROM plate_fixes WHERE wrong = :plate")
    suspend fun delete(plate: String)
}

@Database(entities = [VehicleEntry::class, MonthlyMember::class, PlateFix::class, Capture::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entries(): VehicleEntryDao
    abstract fun members(): MonthlyMemberDao
    abstract fun fixes(): PlateFixDao
    abstract fun captures(): CaptureDao

    companion object {
        /** v1 → v2: parking columns + monthly members table. Keeps existing history. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vehicle_entries ADD COLUMN status TEXT")
                db.execSQL("ALTER TABLE vehicle_entries ADD COLUMN exitDetectedAt INTEGER")
                db.execSQL("ALTER TABLE vehicle_entries ADD COLUMN exitTime INTEGER")
                db.execSQL("ALTER TABLE vehicle_entries ADD COLUMN amount INTEGER")
                db.execSQL("ALTER TABLE vehicle_entries ADD COLUMN token TEXT")
                db.execSQL("ALTER TABLE vehicle_entries ADD COLUMN name TEXT")
                db.execSQL("ALTER TABLE vehicle_entries ADD COLUMN monthly INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `monthly_members` (`plate` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`phone` TEXT NOT NULL, `validTill` INTEGER, `notes` TEXT NOT NULL, PRIMARY KEY(`plate`))"
                )
            }
        }

        /** v2 → v3: vehicle model column. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vehicle_entries ADD COLUMN model TEXT")
            }
        }

        /** v3 → v4: hand-corrected plate numbers. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plate_fixes` (`wrong` TEXT NOT NULL, `correct` TEXT NOT NULL, " +
                        "`correctFormatted` TEXT NOT NULL, PRIMARY KEY(`wrong`))"
                )
            }
        }

        /** v4 → v5: capture waiting list. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `captures` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`plate` TEXT NOT NULL, `plateFormatted` TEXT NOT NULL, `firstSeen` INTEGER NOT NULL, " +
                        "`lastSeen` INTEGER NOT NULL, `reads` INTEGER NOT NULL, `readings` TEXT NOT NULL, " +
                        "`snapshotPath` TEXT, `confidence` REAL NOT NULL, `cameraSummary` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_captures_lastSeen` ON `captures` (`lastSeen`)")
            }
        }

        /** v5 → v6: capture camera + plate position. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE captures ADD COLUMN camId TEXT")
                db.execSQL("ALTER TABLE captures ADD COLUMN posX REAL")
                db.execSQL("ALTER TABLE captures ADD COLUMN posY REAL")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "vehicle_gate.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration()
                .build()
    }
}
