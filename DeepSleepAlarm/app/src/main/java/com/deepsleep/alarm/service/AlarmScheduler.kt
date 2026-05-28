package com.deepsleep.alarm.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.deepsleep.alarm.data.model.AlarmEntity
import com.deepsleep.alarm.data.repository.SleepRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AlarmScheduler
 *
 * AlarmManager へのアラーム登録・キャンセルを一元管理するオブジェクト。
 *
 * Android バージョン別の対応:
 *  - Android 12+ (API 31): canScheduleExactAlarms() チェック必須
 *  - Android 12+ (API 31): setExactAndAllowWhileIdle() を使用
 *  - Android 12未満       : setExact() を使用
 *  - スマートアラーム     : スマートウィンドウ開始時から5分間隔でチェック
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    /**
     * アラームを AlarmManager にスケジュール登録する。
     *
     * @param context       アプリケーションコンテキスト
     * @param alarm         アラームエンティティ（DB保存済み）
     * @param smartWindowMin スマートウィンドウの幅（分）0=スマートアラーム無効
     */
    fun schedule(context: Context, alarm: AlarmEntity, smartWindowMin: Int = 30) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Android 12以上で正確なアラームの権限チェック
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.w(TAG, "SCHEDULE_EXACT_ALARM permission not granted, using inexact alarm")
                scheduleInexact(context, alarmManager, alarm, smartWindowMin)
                return
            }
        }

        if (smartWindowMin > 0) {
            // スマートアラームモード: ウィンドウ開始時から5分間隔でチェック
            scheduleSmartAlarm(context, alarmManager, alarm, smartWindowMin)
        } else {
            // 通常アラームモード: 指定時刻ちょうどに発火
            scheduleExact(context, alarmManager, alarm)
        }
    }

    /**
     * 通常の正確なアラームを登録。
     */
    private fun scheduleExact(context: Context, alarmManager: AlarmManager, alarm: AlarmEntity) {
        val pendingIntent = buildAlarmPendingIntent(context, alarm, AlarmReceiver.ACTION_ALARM_TRIGGER)

        val triggerAtMs = alarm.triggerTimeMs
        Log.d(TAG, "Scheduling exact alarm id=${alarm.id} at ${java.util.Date(triggerAtMs)}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Doze モードでも確実に起動
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        }
    }

    /**
     * スマートアラームを登録。
     * スマートウィンドウ開始時点を最初のチェック時刻とし、
     * ACTION_SMART_CHECK を5分間隔でトリガーする。
     */
    private fun scheduleSmartAlarm(
        context: Context,
        alarmManager: AlarmManager,
        alarm: AlarmEntity,
        smartWindowMin: Int
    ) {
        val windowStartMs = alarm.triggerTimeMs - smartWindowMin * 60_000L
        val nowMs = System.currentTimeMillis()

        // ウィンドウがすでに始まっている場合は直ちにチェック
        val firstCheckMs = maxOf(windowStartMs, nowMs + 5_000L)

        val smartIntent = buildAlarmPendingIntent(context, alarm, AlarmReceiver.ACTION_SMART_CHECK).also {
            // alarm_time_ms を Intent に埋め込む（BootReceiver での復元用）
        }

        Log.d(TAG, "Scheduling smart alarm: window starts ${java.util.Date(windowStartMs)}, first check ${java.util.Date(firstCheckMs)}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, firstCheckMs, smartIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, firstCheckMs, smartIntent)
        }

        // フォールバック: 設定時刻ちょうどにも必ず通常アラームをセット
        scheduleExact(context, alarmManager, alarm)
    }

    /**
     * 正確なアラーム権限がない場合の代替（不正確なアラーム）。
     */
    private fun scheduleInexact(
        context: Context,
        alarmManager: AlarmManager,
        alarm: AlarmEntity,
        smartWindowMin: Int
    ) {
        val pendingIntent = buildAlarmPendingIntent(context, alarm, AlarmReceiver.ACTION_ALARM_TRIGGER)
        alarmManager.set(AlarmManager.RTC_WAKEUP, alarm.triggerTimeMs, pendingIntent)
        Log.w(TAG, "Inexact alarm scheduled for ${java.util.Date(alarm.triggerTimeMs)}")
    }

    /**
     * アラームをキャンセルする。
     */
    fun cancel(context: Context, alarm: AlarmEntity) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // 通常アラームのキャンセル
        buildAlarmPendingIntent(context, alarm, AlarmReceiver.ACTION_ALARM_TRIGGER).let {
            alarmManager.cancel(it)
        }
        // スマートチェックのキャンセル
        buildAlarmPendingIntent(context, alarm, AlarmReceiver.ACTION_SMART_CHECK).let {
            alarmManager.cancel(it)
        }
        Log.d(TAG, "Alarm id=${alarm.id} cancelled")
    }

    /**
     * 再起動後にDBからアラームを復元する。
     */
    fun restoreAlarmsAfterBoot(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val repository = SleepRepository(context)
            val activeAlarms = repository.getActiveAlarms()
            activeAlarms.forEach { alarm ->
                // 既に過去の時刻のアラームはスキップ
                if (alarm.triggerTimeMs > System.currentTimeMillis()) {
                    schedule(context, alarm, alarm.smartWindowMin)
                    Log.d(TAG, "Restored alarm id=${alarm.id}")
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    //  PendingIntent ビルダー
    // ─────────────────────────────────────────────

    private fun buildAlarmPendingIntent(
        context: Context,
        alarm: AlarmEntity,
        action: String
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            this.action = action
            putExtra(AlarmReceiver.EXTRA_ALARM_LABEL, alarm.label)
            putExtra(AlarmReceiver.EXTRA_MUSIC_URI, alarm.musicUri)
            putExtra("extra_alarm_time_ms", alarm.triggerTimeMs)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        // アラームIDをリクエストコードに使用することで、複数アラームを区別
        return PendingIntent.getBroadcast(context, alarm.id.toInt(), intent, flags)
    }
}
