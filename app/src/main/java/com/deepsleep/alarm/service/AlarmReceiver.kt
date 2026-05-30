package com.deepsleep.alarm.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.deepsleep.alarm.ui.AlarmRingActivity
import com.deepsleep.alarm.util.NotificationHelper

/**
 * AlarmReceiver
 *
 * AlarmManager から発火された Intent を受け取るレシーバー。
 * 2種類のアクションを処理する:
 *
 * 1. ACTION_ALARM_TRIGGER  : 通常アラームの発火（設定時刻ちょうど）
 * 2. ACTION_SMART_CHECK    : スマートウィンドウ内の定期チェック（5分間隔）
 *    → SleepMonitorService に問い合わせ、眠りが浅ければアラームを起動
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_ALARM_TRIGGER = "com.deepsleep.alarm.ACTION_ALARM_TRIGGER"
        const val ACTION_SMART_CHECK   = "com.deepsleep.alarm.ACTION_SMART_CHECK"
        const val EXTRA_ALARM_LABEL    = "extra_alarm_label"
        const val EXTRA_MUSIC_URI      = "extra_music_uri"

        /** スマートウィンドウ内のチェック間隔（5分） */
        private const val SMART_CHECK_INTERVAL_MS = 5 * 60 * 1000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive action=${intent.action}")

        when (intent.action) {
            ACTION_ALARM_TRIGGER -> triggerAlarm(context, intent)
            ACTION_SMART_CHECK   -> performSmartCheck(context, intent)
        }
    }

    /**
     * 通常アラームの起動:
     * AlarmRingActivity をフルスクリーンで起動する。
     */
    private fun triggerAlarm(context: Context, intent: Intent) {
        val musicUri   = intent.getStringExtra(EXTRA_MUSIC_URI)
        val alarmLabel = intent.getStringExtra(EXTRA_ALARM_LABEL) ?: "アラーム"

        // ロック画面上でもアクティビティを表示するための Intent フラグ
        val ringIntent = Intent(context, AlarmRingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            putExtra(EXTRA_MUSIC_URI, musicUri)
            putExtra(EXTRA_ALARM_LABEL, alarmLabel)
        }
        context.startActivity(ringIntent)

        // フルスクリーン通知（ロック画面でも表示）
        NotificationHelper.showAlarmNotification(context, alarmLabel, musicUri)
    }

    /**
     * スマートウィンドウ内のチェック:
     * SleepMonitorService が起動中であれば睡眠深度を確認し、
     * 浅い睡眠であればアラームを起動する。
     * そうでなければ次のチェックを5分後にスケジュール。
     */
    private fun performSmartCheck(context: Context, intent: Intent) {
        // サービスが実行中かつ浅い睡眠かどうかを確認
        if (SleepMonitorService.isRunning) {
            // Binder 経由ではなく、static フラグで簡易判定
            // （より精密にするには AIDL や broadcast で連携）
            val shouldRing = checkLightSleepFromService()
            if (shouldRing) {
                triggerAlarm(context, intent)
                return
            }
        }

        // まだウィンドウ内であれば5分後に再チェックをスケジュール
        val alarmTimeMs = intent.getLongExtra("extra_alarm_time_ms", 0L)
        val nowMs = System.currentTimeMillis()
        if (nowMs < alarmTimeMs) {
            scheduleNextSmartCheck(context, intent, nowMs + SMART_CHECK_INTERVAL_MS)
        } else {
            // ウィンドウを過ぎたら通常アラームを強制起動
            triggerAlarm(context, intent)
        }
    }

    private fun checkLightSleepFromService(): Boolean {
        // 実際のサービスのインスタンスには直接アクセスできないため、
        // SharedPreferences を介して最新の睡眠深度を参照する
        // （SleepMonitorService が定期的に書き込む）
        return false // 実装は SleepDataSharedPrefs 経由で行う（後述）
    }

    private fun scheduleNextSmartCheck(context: Context, intent: Intent, triggerAtMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            200,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * BootReceiver
 *
 * 端末再起動後に BOOT_COMPLETED を受信し、
 * 保存済みのアラーム設定を AlarmManager に再登録する。
 * これがないと再起動でアラームが全て消える。
 */
class BootReceiver : BroadcastReceiver() {

    private val TAG = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED") return

        Log.d(TAG, "Boot completed, restoring alarms...")

        // Room DB から有効なアラームを読み込んで再スケジュール
        // （実際の再スケジュール処理は AlarmScheduler に委譲）
        AlarmScheduler.restoreAlarmsAfterBoot(context)
    }
}
