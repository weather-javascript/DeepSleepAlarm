package com.deepsleep.alarm.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.deepsleep.alarm.R
import com.deepsleep.alarm.data.model.SnoreRecord
import com.deepsleep.alarm.data.model.SleepDataPoint
import com.deepsleep.alarm.data.repository.SleepRepository
import com.deepsleep.alarm.ui.MainActivity
import com.deepsleep.alarm.util.NotificationHelper
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * SleepMonitorService
 *
 * 睡眠計測のコアとなるフォアグラウンドサービス。
 * アプリが閉じられた後も動作し続け、以下を担当する:
 *
 * 1. 加速度センサー取得 → 睡眠深度（深い/浅い）の判定
 * 2. MediaRecorder でマイク録音 → いびき検知・録音保存
 * 3. データを Room DB に定期保存
 * 4. スマートアラームトリガー（スマートウィンドウ内に眠りが浅い場合）
 *
 * Android 14以降: foregroundServiceType = microphone|health を使用。
 * WakeLock を保持してCPUのスリープを防ぐ。
 */
class SleepMonitorService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "SleepMonitorService"

        // 通知 ID
        private const val NOTIFICATION_ID = 1001

        // 加速度センサーの閾値（この値以上の動きを「体動あり」とみなす）
        private const val MOVEMENT_THRESHOLD = 0.3f      // m/s²
        private const val DEEP_SLEEP_THRESHOLD = 0.1f    // 深い睡眠判定の閾値

        // いびき検知の音量閾値（dB）
        private const val SNORE_DB_THRESHOLD = 60.0

        // データ記録間隔（ミリ秒）
        private const val RECORD_INTERVAL_MS = 30_000L   // 30秒ごとにDBへ保存

        // いびき検知のサンプリング間隔
        private const val SNORE_CHECK_INTERVAL_MS = 2_000L

        // サービスが実行中かどうかのフラグ（MainActivity から参照）
        @Volatile
        var isRunning = false
            private set

        // Intent アクション
        const val ACTION_START = "com.deepsleep.alarm.START_MONITORING"
        const val ACTION_STOP  = "com.deepsleep.alarm.STOP_MONITORING"

        // Extra キー
        const val EXTRA_SESSION_ID  = "extra_session_id"
        const val EXTRA_ALARM_TIME  = "extra_alarm_time"
        const val EXTRA_SMART_WINDOW_MIN = "extra_smart_window_min"

        fun startIntent(context: Context, sessionId: Long, alarmTimeMs: Long, smartWindowMin: Int): Intent {
            return Intent(context, SleepMonitorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_ALARM_TIME, alarmTimeMs)
                putExtra(EXTRA_SMART_WINDOW_MIN, smartWindowMin)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, SleepMonitorService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }

    // ─────────────────────────────────────────────
    //  バインダー（UI との通信用）
    // ─────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): SleepMonitorService = this@SleepMonitorService
        fun getLatestSleepDepth(): Float = currentSleepDepth
        fun getCurrentSnoreDb(): Double = currentSnoreDb
    }

    private val binder = LocalBinder()

    // ─────────────────────────────────────────────
    //  依存コンポーネント
    // ─────────────────────────────────────────────

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var mediaRecorder: MediaRecorder? = null
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var repository: SleepRepository

    // ─────────────────────────────────────────────
    //  状態変数
    // ─────────────────────────────────────────────

    private var sessionId: Long = -1L
    private var alarmTimeMs: Long = 0L
    private var smartWindowMin: Int = 30

    @Volatile private var currentSleepDepth: Float = 0f   // 0.0 = 深い, 1.0 = 浅い
    @Volatile private var currentSnoreDb: Double = 0.0
    @Volatile private var isSnoring: Boolean = false

    // 加速度センサーの移動平均計算用
    private val movementBuffer = ArrayDeque<Float>(maxOf = 10)
    private var gravityX = 0f; private var gravityY = 0f; private var gravityZ = 0f

    // 現在の録音ファイルパス
    private var currentSnoreFilePath: String? = null
    private var snoreStartTimeMs: Long = 0L

    // Coroutine スコープ
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var dataRecordJob: Job? = null
    private var snoreCheckJob: Job? = null

    // ─────────────────────────────────────────────
    //  ライフサイクル
    // ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        powerManager  = getSystemService(Context.POWER_SERVICE) as PowerManager
        repository    = SleepRepository(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                sessionId      = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                alarmTimeMs    = intent.getLongExtra(EXTRA_ALARM_TIME, 0L)
                smartWindowMin = intent.getIntExtra(EXTRA_SMART_WINDOW_MIN, 30)
                startMonitoring()
            }
            ACTION_STOP -> stopMonitoring()
        }
        // START_STICKY: システムに強制終了されても自動再起動する
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy()")
        stopMonitoring()
    }

    // ─────────────────────────────────────────────
    //  計測開始
    // ─────────────────────────────────────────────

    private fun startMonitoring() {
        if (isRunning) return
        Log.d(TAG, "startMonitoring() sessionId=$sessionId")
        isRunning = true

        // 1. フォアグラウンドサービスとして起動（通知必須）
        startAsForeground()

        // 2. WakeLock を取得してCPUをスリープさせない
        acquireWakeLock()

        // 3. 加速度センサーを登録
        registerAccelerometer()

        // 4. マイク録音を開始（いびき検知）
        startAudioMonitoring()

        // 5. 定期的なデータ保存ループを開始
        startDataRecordingLoop()

        // 6. いびき音量チェックループを開始
        startSnoreCheckLoop()
    }

    // ─────────────────────────────────────────────
    //  フォアグラウンドサービス通知
    // ─────────────────────────────────────────────

    private fun startAsForeground() {
        val notification = buildForegroundNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14以上: foregroundServiceType を明示的に指定
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10–13
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            // Android 8–9
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingIntentFlags
        )

        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            pendingIntentFlags
        )

        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_sleep_monitor)
            .setContentTitle("睡眠計測中")
            .setContentText("加速度センサーとマイクで計測しています")
            .setOngoing(true)            // スワイプで消せないようにする
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setUsesChronometer(true)    // 経過時間を表示
            .setContentIntent(openAppPendingIntent)
            .addAction(
                R.drawable.ic_stop,
                "計測を停止",
                stopPendingIntent
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ─────────────────────────────────────────────
    //  WakeLock
    // ─────────────────────────────────────────────

    private fun acquireWakeLock() {
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DeepSleepAlarm::SleepMonitorWakeLock"
        ).also { lock ->
            // タイムアウト: 12時間（睡眠時間の上限）
            lock.acquire(12 * 60 * 60 * 1000L)
        }
        Log.d(TAG, "WakeLock acquired")
    }

    // ─────────────────────────────────────────────
    //  加速度センサー
    // ─────────────────────────────────────────────

    private fun registerAccelerometer() {
        accelerometer?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL  // 約5Hz（睡眠検知には十分）
            )
            Log.d(TAG, "Accelerometer registered")
        } ?: Log.w(TAG, "Accelerometer not available on this device")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        // ローパスフィルタで重力成分を除去（体動のみを検出）
        val alpha = 0.8f
        gravityX = alpha * gravityX + (1 - alpha) * event.values[0]
        gravityY = alpha * gravityY + (1 - alpha) * event.values[1]
        gravityZ = alpha * gravityZ + (1 - alpha) * event.values[2]

        val linearX = event.values[0] - gravityX
        val linearY = event.values[1] - gravityY
        val linearZ = event.values[2] - gravityZ

        // 合成加速度（体動の強さ）
        val magnitude = sqrt(linearX * linearX + linearY * linearY + linearZ * linearZ)

        // 移動平均バッファに追加
        if (movementBuffer.size >= 10) movementBuffer.removeFirst()
        movementBuffer.addLast(magnitude)

        // 睡眠深度を更新（0.0=深い、1.0=浅い）
        val avgMovement = movementBuffer.average().toFloat()
        currentSleepDepth = (avgMovement / MOVEMENT_THRESHOLD).coerceIn(0f, 1f)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // 精度変化は無視
    }

    /**
     * スマートアラームの判定:
     * 現在が「スマートウィンドウ内」かつ「眠りが浅い（レム睡眠）」であれば true を返す。
     */
    fun shouldTriggerSmartAlarm(): Boolean {
        val nowMs = System.currentTimeMillis()
        val windowStartMs = alarmTimeMs - smartWindowMin * 60_000L
        val inWindow = nowMs in windowStartMs..alarmTimeMs
        val isLightSleep = currentSleepDepth > 0.5f  // 浅い睡眠の閾値
        return inWindow && isLightSleep
    }

    // ─────────────────────────────────────────────
    //  マイク録音・いびき検知
    // ─────────────────────────────────────────────

    private fun startAudioMonitoring() {
        val outputDir = File(filesDir, "snore_recordings").also { it.mkdirs() }
        val outputFile = File(outputDir, "snore_${System.currentTimeMillis()}.m4a")
        currentSnoreFilePath = outputFile.absolutePath

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(applicationContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(currentSnoreFilePath)
                prepare()
                start()
            }
            Log.d(TAG, "MediaRecorder started: $currentSnoreFilePath")
        } catch (e: IOException) {
            Log.e(TAG, "MediaRecorder setup failed", e)
            mediaRecorder = null
        }
    }

    /**
     * いびき音量を定期チェックするコルーチン。
     * MediaRecorder.getMaxAmplitude() で最大振幅を取得し dB に変換。
     */
    private fun startSnoreCheckLoop() {
        snoreCheckJob = serviceScope.launch {
            while (isActive) {
                delay(SNORE_CHECK_INTERVAL_MS)
                checkSnoreLevel()
            }
        }
    }

    private fun checkSnoreLevel() {
        val amplitude = mediaRecorder?.maxAmplitude ?: 0
        if (amplitude > 0) {
            val db = 20 * log10(amplitude.toDouble())
            currentSnoreDb = db

            if (db >= SNORE_DB_THRESHOLD) {
                if (!isSnoring) {
                    isSnoring = true
                    snoreStartTimeMs = System.currentTimeMillis()
                    Log.d(TAG, "Snore detected: ${db}dB")
                }
            } else {
                if (isSnoring) {
                    isSnoring = false
                    // いびきイベントを DB に保存
                    val duration = System.currentTimeMillis() - snoreStartTimeMs
                    if (duration > 3_000L) {  // 3秒以上続いたいびきのみ記録
                        serviceScope.launch {
                            repository.insertSnoreRecord(
                                SnoreRecord(
                                    sessionId   = sessionId,
                                    startTimeMs = snoreStartTimeMs,
                                    durationMs  = duration,
                                    maxDb       = db,
                                    filePath    = currentSnoreFilePath
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    //  定期データ記録
    // ─────────────────────────────────────────────

    private fun startDataRecordingLoop() {
        dataRecordJob = serviceScope.launch {
            while (isActive) {
                delay(RECORD_INTERVAL_MS)
                saveDataPoint()
            }
        }
    }

    private suspend fun saveDataPoint() {
        repository.insertSleepDataPoint(
            SleepDataPoint(
                sessionId   = sessionId,
                timestampMs = System.currentTimeMillis(),
                sleepDepth  = currentSleepDepth,
                snoreDb     = currentSnoreDb
            )
        )
    }

    // ─────────────────────────────────────────────
    //  計測停止
    // ─────────────────────────────────────────────

    private fun stopMonitoring() {
        Log.d(TAG, "stopMonitoring()")
        isRunning = false

        // コルーチン停止
        serviceScope.cancel()

        // センサー解除
        sensorManager.unregisterListener(this)

        // MediaRecorder 停止
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder stop error", e)
        }
        mediaRecorder = null

        // WakeLock 解放
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock release error", e)
        }
        wakeLock = null

        // フォアグラウンドサービス停止
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
