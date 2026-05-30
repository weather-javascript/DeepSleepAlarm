package com.deepsleep.alarm.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.deepsleep.alarm.R
import com.deepsleep.alarm.ui.AlarmRingActivity
import com.deepsleep.alarm.util.NotificationHelper

/**
 * AlarmPlaybackService
 *
 * アラーム音楽をフォアグラウンドサービスとして再生する。
 * AlarmRingActivity と連携し、ロック画面を消した後も
 * 音楽が止まらないようにする。
 *
 * Android 14+: foregroundServiceType = mediaPlayback
 */
class AlarmPlaybackService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 3001
        const val ACTION_START  = "com.deepsleep.alarm.PLAYBACK_START"
        const val ACTION_STOP   = "com.deepsleep.alarm.PLAYBACK_STOP"
        const val EXTRA_URI     = "extra_music_uri"
        const val EXTRA_LABEL   = "extra_alarm_label"

        fun startIntent(context: Context, musicUri: String?, label: String): Intent =
            Intent(context, AlarmPlaybackService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URI, musicUri)
                putExtra(EXTRA_LABEL, label)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, AlarmPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
    }

    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val uri   = intent.getStringExtra(EXTRA_URI)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "アラーム"
                startPlayback(uri, label)
            }
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(musicUri: String?, label: String) {
        // フォアグラウンド通知
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE else 0

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmRingActivity::class.java),
            pendingFlags
        )

        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("アラーム再生中")
            .setContentText(label)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // ExoPlayer で再生
        player = ExoPlayer.Builder(this).build().also { p ->
            val item = if (!musicUri.isNullOrEmpty()) {
                MediaItem.fromUri(Uri.parse(musicUri))
            } else {
                MediaItem.fromUri(android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI)
            }
            p.setMediaItem(item)
            p.repeatMode = ExoPlayer.REPEAT_MODE_ONE
            p.prepare()
            p.play()
        }
    }

    private fun stopPlayback() {
        player?.apply { stop(); release() }
        player = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
