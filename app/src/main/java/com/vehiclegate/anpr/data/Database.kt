package com.vehiclegate.anpr.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vehicle_entries", indices = [Index("entryTime"), Index("plate")])
data class VehicleEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Compact plate, e.g. TN09AB1234 */
    val plate: String,
    /** Display plate, e.g. TN 09 AB 1234 */
    val plateFormatted: String,
    /** First time any camera saw the vehicle (= entry time), epoch ms */
    val entryTime: Long,
    val lastSeenTime: Long,
    /** Fused confidence 0..1 */
    val confidence: Float,
    val totalReads: Int,
    val cameraCount: Int,
    /** Human-readable per-camera breakdown, one camera per line: "Gate Cam 1|TN 09 AB 1234|92|3|1" */
    val cameraSummary: String,
    val snapshotPath: String?
)

@Dao
interface VehicleEntryDao {
    @Query("SELECT * FROM vehicle_entries ORDER BY entryTime DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<VehicleEntry>>

    @Query("SELECT COUNT(*) FROM vehicle_entries WHERE entryTime >= :since")
    fun countSince(since: Long): Flow<Int>

    @Query("SELECT * FROM vehicle_entries ORDER BY entryTime DESC")
    suspend fun all(): List<VehicleEntry>

    @Insert
    suspend fun insert(entry: VehicleEntry): Long

    @Query("SELECT snapshotPath FROM vehicle_entries WHERE entryTime < :before")
    suspend fun snapshotsBefore(before: Long): List<String?>

    @Query("DELETE FROM vehicle_entries WHERE entryTime < :before")
    suspend fun deleteBefore(before: Long): Int

    @Query("DELETE FROM vehicle_entries")
    suspend fun clear()
}

@Database(entities = [VehicleEntry::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entries(): VehicleEntryDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "vehicle_gate.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
