package com.deepsleep.alarm.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// ─────────────────────────────────────────────────────────────────────────────
//  アラームエンティティ
// ─────────────────────────────────────────────────────────────────────────────

/**
 * アラーム設定を保存するテーブル。
 *
 * @param id            自動採番ID
 * @param label         アラームのラベル（例: "起床"）
 * @param triggerTimeMs アラームを鳴らす Unix タイムスタンプ（ミリ秒）
 * @param musicUri      アラーム音として使用する音楽ファイルの URI
 * @param smartWindowMin スマートウィンドウ幅（分）。0=スマートアラーム無効
 * @param isEnabled     アラームが有効かどうか
 * @param repeatDays    曜日繰り返しフラグ（Bit 0=日, 1=月, ..., 6=土）
 * @param createdAt     作成日時
 */
@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val label: String = "アラーム",
    val triggerTimeMs: Long,
    val musicUri: String? = null,
    val smartWindowMin: Int = 30,
    val isEnabled: Boolean = true,
    val repeatDays: Int = 0,    // ビットマスク
    val createdAt: Long = System.currentTimeMillis()
)

// ─────────────────────────────────────────────────────────────────────────────
//  睡眠セッションエンティティ
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 1回の睡眠セッション（就寝～起床）を表すテーブル。
 *
 * @param id           自動採番ID
 * @param bedTimeMs    就床時間
 * @param sleepTimeMs  入眠時間（推定）
 * @param wakeTimeMs   起床時間（0=まだ起床していない）
 * @param targetWakeMs 設定アラーム時刻
 * @param efficiency   睡眠効率（0.0〜1.0）。wakeTime確定後に計算
 * @param note         ユーザーメモ
 */
@Entity(tableName = "sleep_sessions")
data class SleepSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bedTimeMs: Long = System.currentTimeMillis(),
    val sleepTimeMs: Long = 0L,
    val wakeTimeMs: Long = 0L,
    val targetWakeMs: Long = 0L,
    val efficiency: Float = 0f,
    val note: String = ""
)

// ─────────────────────────────────────────────────────────────────────────────
//  睡眠深度データポイント
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 30秒ごとに記録される睡眠深度のデータポイント。
 * グラフ表示に使用する。
 *
 * @param id          自動採番ID
 * @param sessionId   紐づく睡眠セッションID
 * @param timestampMs 記録時刻
 * @param sleepDepth  睡眠深度（0.0=深い睡眠, 1.0=浅い睡眠/覚醒）
 * @param snoreDb     そのタイミングのいびき音量（dB）
 */
@Entity(
    tableName = "sleep_data_points",
    foreignKeys = [ForeignKey(
        entity = SleepSession::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class SleepDataPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val timestampMs: Long,
    val sleepDepth: Float,   // 0.0 〜 1.0
    val snoreDb: Double = 0.0
)

// ─────────────────────────────────────────────────────────────────────────────
//  いびき録音レコード
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 検知したいびきのイベントを保存するテーブル。
 *
 * @param id           自動採番ID
 * @param sessionId    紐づく睡眠セッションID
 * @param startTimeMs  いびき開始時刻
 * @param durationMs   いびきの継続時間（ミリ秒）
 * @param maxDb        最大音量（dB）
 * @param filePath     録音ファイルのパス（nullable: ファイルが削除済みの場合）
 */
@Entity(
    tableName = "snore_records",
    foreignKeys = [ForeignKey(
        entity = SleepSession::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class SnoreRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val startTimeMs: Long,
    val durationMs: Long,
    val maxDb: Double,
    val filePath: String? = null
)
