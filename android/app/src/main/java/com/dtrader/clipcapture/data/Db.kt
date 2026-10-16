package com.dtrader.clipcapture.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    /** CameraSelector.LENS_FACING_FRONT or LENS_FACING_BACK. */
    val lensFacing: Int,
    /** true = 1080x1920 portrait, false = 1920x1080 landscape. */
    val portrait: Boolean,
    /** JSON of the locked camera values (CameraLockValues), null until calibrated. */
    val cameraSettingsJson: String? = null,
    /** Path to reference.png / reference.json, null until the neutral pose is saved. */
    val referenceImagePath: String? = null,
    val referenceDataPath: String? = null,
)

@Entity(
    tableName = "clips",
    indices = [Index("sessionId")],
)
data class ClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** 1-based number within the session; also used in the file name (clip_###.mp4). */
    val number: Int,
    /** The continuous recording this clip was cut from. */
    val rawPath: String,
    /** Cut points inside [rawPath], in ms. */
    val startMs: Long,
    val endMs: Long,
    /** The cut clip (clip_###.mp4), null until exported. */
    val clipPath: String? = null,
    /** PENDING, DONE or FAILED. */
    val exportStatus: String = "PENDING",
    val exportError: String? = null,
    val createdAt: Long,
    /** "calm" or "active". */
    val tag: String,
    val durationMs: Long,
    /** JSON array of flags, e.g. ["end_not_neutral"]. */
    val flagsJson: String,
    /** Preliminary QC from the live analysis: PASS, WARN or RETAKE. */
    val qcStatus: String,
    /** JSON array of human-readable QC reasons. */
    val qcReasonsJson: String,
    /** JSON of the live-analysis statistics the QC was computed from. */
    val liveStatsJson: String,
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)
}

@Dao
interface ClipDao {
    @Query("SELECT * FROM clips WHERE sessionId = :sessionId ORDER BY number")
    fun observeForSession(sessionId: Long): Flow<List<ClipEntity>>

    @Query("SELECT COALESCE(MAX(number), 0) FROM clips WHERE sessionId = :sessionId")
    suspend fun maxNumber(sessionId: Long): Int

    @Query("SELECT * FROM clips WHERE sessionId = :sessionId")
    suspend fun listForSession(sessionId: Long): List<ClipEntity>

    @Query("SELECT * FROM clips WHERE sessionId = :sessionId AND exportStatus = 'PENDING' ORDER BY number")
    suspend fun pending(sessionId: Long): List<ClipEntity>

    @Insert
    suspend fun insert(clip: ClipEntity): Long

    @Update
    suspend fun update(clip: ClipEntity)

    @Delete
    suspend fun delete(clip: ClipEntity)
}

@Database(entities = [SessionEntity::class, ClipEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun clips(): ClipDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "clipcapture.db")
                    // Pre-release schema: clips from 0.2 (single-shot mode) aren't migrated.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
