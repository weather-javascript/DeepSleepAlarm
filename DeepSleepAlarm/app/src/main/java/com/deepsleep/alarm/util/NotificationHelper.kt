package com.deepsleep.alarm.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.deepsleep.alarm.R
import com.deepsleep.alarm.ui.AlarmRingActivity
import com.deepsleep.alarm.service.AlarmReceiver

/**
 * NotificationHelper
 *
 * アプリで使用するすべての通知チャンネルと通知を管理するユーティリティ。
 *
 * Android 8.0（Oreo, API 26）以降ではチャンネルの作成が必須。
 * アプリ起動時（Application.onCreate() または MainActivity.onCreate()）に
 * createNotificationChannels() を呼び出すこと。
 *
 * 通知チャンネル一覧:
 *  - CHANNEL_MONITORING : 睡眠計測中の常駐通知（低優先度）
 *  - CHANNEL_ALARM      : アラーム発火通知（最高優先度）
 */
object NotificationHelper {

    // ─────────────────────────────────────────────
    //  チャンネルID 定数
    // ─────────────────────────────────────────────

    const val CHANNEL_MONITORING = "channel_sleep_monitoring"
    const val CHANNEL_ALARM      = "channel_alarm"

    // 通知ID
    private const val NOTIFICATION_ID_ALARM = 2001

    // ─────────────────────────────────────────────
    //  通知チャンネルの作成
    // ─────────────────────────────────────────────

    /**
     * アプリ起動時に必ず呼び出す。
     * Android 8.0以上では、この処理をしないと通知が表示されない。
     * 既存のチャンネルがある場合は何もしない（冪等）。
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // ① 睡眠計測中の常駐通知チャンネル
        val monitoringChannel = NotificationChannel(
            CHANNEL_MONITORING,
            "睡眠計測",
            NotificationManager.IMPORTANCE_LOW  // 低優先度（音なし・バナーなし）
        ).apply {
            description = "バックグラウンドで睡眠を計測中に表示されます"
            setShowBadge(false)         // アイコンバッジ非表示
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        // ② アラーム通知チャンネル
        val alarmChannel = NotificationChannel(
            CHANNEL_ALARM,
            "アラーム",
            NotificationManager.IMPORTANCE_HIGH  // 最高優先度（ヘッドアップ通知）
        ).apply {
            description = "アラームが鳴るときに表示されます"
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            enableLights(true)
        }

        notificationManager.createNotificationChannel(monitoringChannel)
        notificationManager.createNotificationChannel(alarmChannel)
    }

    // ─────────────────────────────────────────────
    //  アラーム通知の表示
    // ─────────────────────────────────────────────

    /**
     * ロック画面にも表示されるフルスクリーンアラーム通知を表示する。
     * Android 10以上では USE_FULL_SCREEN_INTENT 権限が必要。
     *
     * @param context    コンテキスト
     * @param label      アラームのラベル
     * @param musicUri   アラーム音のURI（null の場合はデフォルト音）
     */
    fun showAlarmNotification(context: Context, label: String, musicUri: String?) {
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        // アラーム画面への Intent
        val fullScreenIntent = Intent(context, AlarmRingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY
            putExtra(AlarmReceiver.EXTRA_ALARM_LABEL, label)
            putExtra(AlarmReceiver.EXTRA_MUSIC_URI, musicUri)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID_ALARM, fullScreenIntent, pendingIntentFlags
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("アラーム")
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            // フルスクリーン Intent: ロック画面上でも AlarmRingActivity を全画面表示
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .build()

        val notificationManager = NotificationManagerCompat.from(context)
        // Android 13以上では POST_NOTIFICATIONS 権限チェックが必要
        // （権限なしで notify() を呼ぶと SecurityException が発生する）
        try {
            notificationManager.notify(NOTIFICATION_ID_ALARM, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS が拒否されている場合はアクティビティ起動のみで対処
            context.startActivity(fullScreenIntent)
        }
    }

    /**
     * アラーム通知を消去する（アラームを止めたとき）。
     */
    fun cancelAlarmNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_ALARM)
    }
}
