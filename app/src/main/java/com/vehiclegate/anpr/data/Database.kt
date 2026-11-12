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

    /** Sessions not yet closed (parked or seen leaving). */
    @Query("SELECT * FROM vehicle_entries WHERE status IN ('IN','EXIT') ORDER BY entryTime DESC")
    suspend fun openSessions(): List<VehicleEntry>

    @Query("SELECT * FROM vehicle_entries WHERE status = 'OUT' AND exitTime >= :since")
    suspend fun closedSince(since: Long): List<VehicleEntry>

    @Query("SELECT name FROM vehicle_entries WHERE plate = :plate AND name IS NOT NULL AND name != '' ORDER BY entryTime DESC LIMIT 1")
    suspend fun lastNameFor(plate: String): String?

    @Query("SELECT model FROM vehicle_entries WHERE plate = :plate AND model IS NOT NULL AND model != '' ORDER BY entryTime DESC LIMIT 1")
    suspend fun lastModelFor(plate: String): String?

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

@Database(entities = [VehicleEntry::class, MonthlyMember::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entries(): VehicleEntryDao
    abstract fun members(): MonthlyMemberDao

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

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "vehicle_gate.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
    }
}
