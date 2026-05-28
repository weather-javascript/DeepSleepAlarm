package com.deepsleep.alarm.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.deepsleep.alarm.databinding.ActivityAlarmRingBinding
import com.deepsleep.alarm.service.AlarmReceiver
import com.deepsleep.alarm.service.SleepMonitorService
import com.deepsleep.alarm.util.NotificationHelper

/**
 * AlarmRingActivity
 *
 * アラーム発火時にロック画面上でフルスクリーン表示されるActivity。
 *
 * 責務:
 * - アラーム音（ExoPlayer）の再生
 * - バイブレーション
 * - スヌーズ・停止ボタンの提供
 * - 睡眠計測サービスの停止
 *
 * ロック画面上での表示はフラグで制御（Android 8.0以上で有効）。
 */
class AlarmRingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmRingBinding

    private var exoPlayer: ExoPlayer? = null
    private var vibrator: Vibrator? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    companion object {
        private const val SNOOZE_MINUTES = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ロック画面上でもこのActivityを表示するための設定
        setupWindowForLockScreen()

        binding = ActivityAlarmRingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val label    = intent.getStringExtra(AlarmReceiver.EXTRA_ALARM_LABEL) ?: "アラーム"
        val musicUri = intent.getStringExtra(AlarmReceiver.EXTRA_MUSIC_URI)

        binding.tvAlarmLabel.text = label

        // アラーム音を再生
        startAlarmPlayback(musicUri)

        // バイブレーション開始
        startVibration()

        // ボタン設定
        binding.btnStop.setOnClickListener {
            stopAlarm()
        }
        binding.btnSnooze.setOnClickListener {
            snoozeAlarm()
        }
    }

    // ─────────────────────────────────────────────
    //  ロック画面表示設定
    // ─────────────────────────────────────────────

    private fun setupWindowForLockScreen() {
        // 画面をオンにする
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        // フルスクリーン表示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    // ─────────────────────────────────────────────
    //  アラーム音再生（ExoPlayer）
    // ─────────────────────────────────────────────

    private fun startAlarmPlayback(musicUri: String?) {
        // オーディオフォーカスを取得（他のアプリの音楽を一時停止）
        requestAudioFocus()

        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            val mediaItem = if (!musicUri.isNullOrEmpty()) {
                MediaItem.fromUri(Uri.parse(musicUri))
            } else {
                // ユーザーが音楽を選択していない場合はデフォルトのアラーム音
                val defaultAlarmUri = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
                MediaItem.fromUri(defaultAlarmUri)
            }
            player.setMediaItem(mediaItem)
            player.repeatMode = ExoPlayer.REPEAT_MODE_ONE  // ループ再生
            player.prepare()
            player.play()
        }
    }

    private fun requestAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .build()
                .also { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    // ─────────────────────────────────────────────
    //  バイブレーション
    // ─────────────────────────────────────────────

    private fun startVibration() {
        val pattern = longArrayOf(0, 500, 300, 500)  // 0ms待機, 500ms振動, 300ms停止, 繰り返し

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(pattern, 0)  // 0=ループインデックス
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    // ─────────────────────────────────────────────
    //  アラーム停止・スヌーズ
    // ─────────────────────────────────────────────

    private fun stopAlarm() {
        releaseResources()

        // 睡眠計測サービスを停止
        stopService(SleepMonitorService.stopIntent(this))

        // 通知を消去
        NotificationHelper.cancelAlarmNotification(this)

        finish()
    }

    private fun snoozeAlarm() {
        releaseResources()

        // スヌーズ: N分後に再度アラームを鳴らす
        // （AlarmManager に再スケジュールする）
        val snoozeTimeMs = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        // TODO: AlarmScheduler.scheduleOneShot(this, snoozeTimeMs) を呼び出す

        NotificationHelper.cancelAlarmNotification(this)
        finish()
    }

    private fun releaseResources() {
        exoPlayer?.apply {
            stop()
            release()
        }
        exoPlayer = null

        vibrator?.cancel()
        vibrator = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.abandonAudioFocusRequest(it)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseResources()
    }

    // 戻るボタンでアラームを止めさせない
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 何もしない（アラームを無視させない）
    }
}
