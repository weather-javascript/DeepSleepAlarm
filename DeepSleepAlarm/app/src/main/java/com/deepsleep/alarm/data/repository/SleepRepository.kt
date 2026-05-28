package com.deepsleep.alarm.data.repository

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.deepsleep.alarm.data.model.*
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
//  Room Database
// ─────────────────────────────────────────────────────────────────────────────

@Database(
    entities = [
        AlarmEntity::class,
        SleepSession::class,
        SleepDataPoint::class,
        SnoreRecord::class
    ],
    version = 1,
    exportSchema = true
)
abstract class SleepDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun sleepSessionDao(): SleepSessionDao
    abstract fun sleepDataPointDao(): SleepDataPointDao
    abstract fun snoreRecordDao(): SnoreRecordDao

    companion object {
        @Volatile private var INSTANCE: SleepDatabase? = null

        fun getInstance(context: Context): SleepDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SleepDatabase::class.java,
                    "deep_sleep_alarm.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Repository（DBアクセスの一元管理）
// ─────────────────────────────────────────────────────────────────────────────

class SleepRepository(context: Context) {

    private val db = SleepDatabase.getInstance(context)
    private val alarmDao         = db.alarmDao()
    private val sessionDao       = db.sleepSessionDao()
    private val dataPointDao     = db.sleepDataPointDao()
    private val snoreRecordDao   = db.snoreRecordDao()

    // ── アラーム操作 ──────────────────────────────────

    fun getAllAlarmsFlow(): Flow<List<AlarmEntity>> = alarmDao.getAllAlarmsFlow()

    suspend fun insertAlarm(alarm: AlarmEntity): Long = alarmDao.insert(alarm)

    suspend fun updateAlarm(alarm: AlarmEntity) = alarmDao.update(alarm)

    suspend fun deleteAlarm(alarm: AlarmEntity) = alarmDao.delete(alarm)

    suspend fun setAlarmEnabled(id: Long, enabled: Boolean) = alarmDao.setEnabled(id, enabled)

    suspend fun getActiveAlarms(): List<AlarmEntity> = alarmDao.getActiveAlarms()

    // ── 睡眠セッション操作 ────────────────────────────

    fun getRecentSessionsFlow(): Flow<List<SleepSession>> = sessionDao.getRecentSessionsFlow()

    suspend fun startNewSession(targetWakeMs: Long): Long {
        val session = SleepSession(
            bedTimeMs    = System.currentTimeMillis(),
            targetWakeMs = targetWakeMs
        )
        return sessionDao.insert(session)
    }

    suspend fun finishSession(sessionId: Long, wakeTimeMs: Long) {
        val session = sessionDao.getById(sessionId) ?: return
        val efficiency = calculateEfficiency(session, wakeTimeMs)
        sessionDao.update(
            session.copy(
                wakeTimeMs = wakeTimeMs,
                efficiency  = efficiency
            )
        )
    }

    suspend fun getLatestSession(): SleepSession? = sessionDao.getLatest()

    // ── 睡眠データポイント操作 ────────────────────────

    suspend fun insertSleepDataPoint(dataPoint: SleepDataPoint) =
        dataPointDao.insert(dataPoint)

    fun getDataPointsFlow(sessionId: Long): Flow<List<SleepDataPoint>> =
        dataPointDao.getDataPointsForSession(sessionId)

    suspend fun getDataPointsSync(sessionId: Long): List<SleepDataPoint> =
        dataPointDao.getDataPointsForSessionSync(sessionId)

    // ── いびきレコード操作 ────────────────────────────

    suspend fun insertSnoreRecord(record: SnoreRecord): Long =
        snoreRecordDao.insert(record)

    fun getSnoreRecordsFlow(sessionId: Long): Flow<List<SnoreRecord>> =
        snoreRecordDao.getSnoreRecordsForSession(sessionId)

    // ── 統計計算 ──────────────────────────────────────

    /**
     * 睡眠効率を計算する。
     * 睡眠効率（%）= 実際の睡眠時間 ÷ 就床時間 × 100
     */
    private fun calculateEfficiency(session: SleepSession, wakeTimeMs: Long): Float {
        val totalBedTimeMs = wakeTimeMs - session.bedTimeMs
        if (totalBedTimeMs <= 0L) return 0f

        // 睡眠時間 = 就床時間 - 覚醒時間（入眠時間〜起床時間）
        val sleepStart = if (session.sleepTimeMs > 0) session.sleepTimeMs else session.bedTimeMs
        val actualSleepMs = wakeTimeMs - sleepStart
        return (actualSleepMs.toFloat() / totalBedTimeMs).coerceIn(0f, 1f)
    }

    /**
     * セッションの統計サマリーを計算する。
     *
     * @return SleepStatsSummary（就床時間・睡眠時間・睡眠効率・いびき回数）
     */
    suspend fun calcSessionStats(sessionId: Long): SleepStatsSummary {
        val session   = sessionDao.getById(sessionId) ?: return SleepStatsSummary()
        val dataPoints = dataPointDao.getDataPointsForSessionSync(sessionId)

        val totalBedMs   = if (session.wakeTimeMs > 0) session.wakeTimeMs - session.bedTimeMs else 0L
        val totalSleepMs = if (session.wakeTimeMs > 0 && session.sleepTimeMs > 0)
            session.wakeTimeMs - session.sleepTimeMs else 0L

        // 睡眠深度ヒストグラム（浅い・深い・覚醒の割合）
        val deepCount   = dataPoints.count { it.sleepDepth < 0.3f }
        val lightCount  = dataPoints.count { it.sleepDepth in 0.3f..0.7f }
        val awakeCount  = dataPoints.count { it.sleepDepth > 0.7f }
        val total       = dataPoints.size.coerceAtLeast(1)

        return SleepStatsSummary(
            bedTimeMs        = session.bedTimeMs,
            wakeTimeMs       = session.wakeTimeMs,
            totalBedMs       = totalBedMs,
            totalSleepMs     = totalSleepMs,
            efficiency       = session.efficiency,
            deepSleepRatio   = deepCount.toFloat() / total,
            lightSleepRatio  = lightCount.toFloat() / total,
            awakeRatio       = awakeCount.toFloat() / total
        )
    }
}

/**
 * 睡眠統計のサマリーデータクラス。
 */
data class SleepStatsSummary(
    val bedTimeMs: Long     = 0L,
    val wakeTimeMs: Long    = 0L,
    val totalBedMs: Long    = 0L,
    val totalSleepMs: Long  = 0L,
    val efficiency: Float   = 0f,
    val deepSleepRatio: Float   = 0f,
    val lightSleepRatio: Float  = 0f,
    val awakeRatio: Float       = 0f
)
