package com.deepsleep.alarm.data.model

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
//  AlarmDao
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY triggerTimeMs ASC")
    fun getAllAlarmsFlow(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms WHERE isEnabled = 1 AND triggerTimeMs > :nowMs")
    suspend fun getActiveAlarms(nowMs: Long = System.currentTimeMillis()): List<AlarmEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alarm: AlarmEntity): Long

    @Update
    suspend fun update(alarm: AlarmEntity)

    @Delete
    suspend fun delete(alarm: AlarmEntity)

    @Query("UPDATE alarms SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}

// ─────────────────────────────────────────────────────────────────────────────
//  SleepSessionDao
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface SleepSessionDao {
    @Query("SELECT * FROM sleep_sessions ORDER BY bedTimeMs DESC LIMIT 30")
    fun getRecentSessionsFlow(): Flow<List<SleepSession>>

    @Query("SELECT * FROM sleep_sessions WHERE id = :id")
    suspend fun getById(id: Long): SleepSession?

    @Insert
    suspend fun insert(session: SleepSession): Long

    @Update
    suspend fun update(session: SleepSession)

    @Query("SELECT * FROM sleep_sessions ORDER BY bedTimeMs DESC LIMIT 1")
    suspend fun getLatest(): SleepSession?
}

// ─────────────────────────────────────────────────────────────────────────────
//  SleepDataPointDao
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface SleepDataPointDao {
    @Query("SELECT * FROM sleep_data_points WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    fun getDataPointsForSession(sessionId: Long): Flow<List<SleepDataPoint>>

    @Query("SELECT * FROM sleep_data_points WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getDataPointsForSessionSync(sessionId: Long): List<SleepDataPoint>

    @Insert
    suspend fun insert(dataPoint: SleepDataPoint)
}

// ─────────────────────────────────────────────────────────────────────────────
//  SnoreRecordDao
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface SnoreRecordDao {
    @Query("SELECT * FROM snore_records WHERE sessionId = :sessionId ORDER BY startTimeMs ASC")
    fun getSnoreRecordsForSession(sessionId: Long): Flow<List<SnoreRecord>>

    @Insert
    suspend fun insert(snoreRecord: SnoreRecord): Long

    @Delete
    suspend fun delete(snoreRecord: SnoreRecord)
}
