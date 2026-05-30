package com.deepsleep.alarm.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.deepsleep.alarm.R
import com.deepsleep.alarm.data.model.AlarmEntity
import com.deepsleep.alarm.data.repository.SleepRepository
import com.deepsleep.alarm.databinding.FragmentAlarmBinding
import com.deepsleep.alarm.service.AlarmScheduler
import com.deepsleep.alarm.service.SleepMonitorService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * AlarmFragment
 *
 * アラーム設定画面。
 * - TimePicker でアラーム時刻を設定
 * - ローカル音楽ファイルをアラーム音として選択
 * - スマートウィンドウの幅（0〜60分）を設定
 * - 計測開始ボタンで SleepMonitorService を起動
 */
class AlarmFragment : Fragment() {

    private var _binding: FragmentAlarmBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: SleepRepository

    // 選択されたアラーム時刻（Calendar）
    private var selectedAlarmCalendar: Calendar = Calendar.getInstance().apply {
        add(Calendar.HOUR_OF_DAY, 8)  // デフォルト: 8時間後
    }

    // 選択された音楽ファイルの URI
    private var selectedMusicUri: Uri? = null

    // 音楽ファイル選択ランチャー
    private val musicPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedMusicUri = it
            // 取得した URI への永続的なアクセス権を付与
            requireContext().contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            binding.tvSelectedMusic.text = getMusicFileName(it)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlarmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = SleepRepository(requireContext())

        setupUI()
        updateAlarmTimeDisplay()
    }

    private fun setupUI() {
        // アラーム時刻設定ボタン
        binding.btnSetTime.setOnClickListener {
            showTimePicker()
        }

        // 音楽ファイル選択ボタン
        binding.btnSelectMusic.setOnClickListener {
            selectMusicFile()
        }

        // スマートウィンドウのスライダー
        binding.sliderSmartWindow.addOnChangeListener { _, value, _ ->
            val minutes = value.toInt()
            binding.tvSmartWindowLabel.text = if (minutes == 0) {
                "スマートアラーム: オフ"
            } else {
                "スマートウィンドウ: ${minutes}分前から検知"
            }
        }

        // 計測開始ボタン
        binding.btnStartMonitoring.setOnClickListener {
            if (SleepMonitorService.isRunning) {
                stopMonitoring()
            } else {
                startMonitoring()
            }
        }

        // サービス状態に応じてボタンラベルを更新
        binding.btnStartMonitoring.text = if (SleepMonitorService.isRunning) {
            "計測を停止"
        } else {
            "計測を開始"
        }
    }

    // ─────────────────────────────────────────────
    //  時刻設定
    // ─────────────────────────────────────────────

    private fun showTimePicker() {
        TimePickerDialog(
            requireContext(),
            { _, hourOfDay, minute ->
                selectedAlarmCalendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hourOfDay)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    // 設定時刻が過去なら翌日に設定
                    if (before(Calendar.getInstance())) {
                        add(Calendar.DAY_OF_MONTH, 1)
                    }
                }
                updateAlarmTimeDisplay()
            },
            selectedAlarmCalendar.get(Calendar.HOUR_OF_DAY),
            selectedAlarmCalendar.get(Calendar.MINUTE),
            true  // 24時間表示
        ).show()
    }

    private fun updateAlarmTimeDisplay() {
        val format = SimpleDateFormat("HH:mm", Locale.JAPAN)
        binding.tvAlarmTime.text = format.format(selectedAlarmCalendar.time)
    }

    // ─────────────────────────────────────────────
    //  音楽ファイル選択
    // ─────────────────────────────────────────────

    private fun selectMusicFile() {
        // Android バージョンに応じたパーミッションチェック
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(requireContext(), permission)
            == PackageManager.PERMISSION_GRANTED
        ) {
            musicPickerLauncher.launch("audio/*")
        } else {
            Toast.makeText(
                requireContext(),
                "音楽ファイルを選択するには「${if (Build.VERSION.SDK_INT >= 33) "メディアとファイル" else "ストレージ"}」権限が必要です",
                Toast.LENGTH_SHORT
            ).show()
            // MainActivity のパーミッション要求フローを再実行
            (activity as? MainActivity)?.let {
                // MainActivity の requestRuntimePermissions() を呼ぶ
            }
        }
    }

    private fun getMusicFileName(uri: Uri): String {
        return try {
            requireContext().contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else null
            } ?: uri.lastPathSegment ?: "選択済み"
        } catch (e: Exception) {
            uri.lastPathSegment ?: "選択済み"
        }
    }

    // ─────────────────────────────────────────────
    //  計測開始・停止
    // ─────────────────────────────────────────────

    private fun startMonitoring() {
        val alarmTimeMs    = selectedAlarmCalendar.timeInMillis
        val smartWindowMin = binding.sliderSmartWindow.value.toInt()

        lifecycleScope.launch {
            // 1. DBにセッションを作成
            val sessionId = repository.startNewSession(alarmTimeMs)

            // 2. アラームを AlarmManager に登録
            val alarmEntity = AlarmEntity(
                label         = "起床アラーム",
                triggerTimeMs = alarmTimeMs,
                musicUri      = selectedMusicUri?.toString(),
                smartWindowMin = smartWindowMin
            )
            val alarmId = repository.insertAlarm(alarmEntity)
            AlarmScheduler.schedule(
                requireContext(),
                alarmEntity.copy(id = alarmId),
                smartWindowMin
            )

            // 3. フォアグラウンドサービスを起動
            val serviceIntent = SleepMonitorService.startIntent(
                requireContext(), sessionId, alarmTimeMs, smartWindowMin
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(serviceIntent)
            } else {
                requireContext().startService(serviceIntent)
            }

            binding.btnStartMonitoring.text = "計測を停止"
            Toast.makeText(requireContext(), "睡眠計測を開始しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopMonitoring() {
        requireContext().startService(SleepMonitorService.stopIntent(requireContext()))
        binding.btnStartMonitoring.text = "計測を開始"
        Toast.makeText(requireContext(), "睡眠計測を停止しました", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
