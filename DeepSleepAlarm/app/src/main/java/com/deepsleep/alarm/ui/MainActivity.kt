package com.deepsleep.alarm.ui

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.deepsleep.alarm.R
import com.deepsleep.alarm.databinding.ActivityMainBinding
import com.deepsleep.alarm.service.SleepMonitorService
import com.deepsleep.alarm.util.NotificationHelper

/**
 * MainActivity
 *
 * アプリのエントリーポイント。
 * 起動時に必要な全ランタイムパーミッションを要求し、
 * BottomNavigationView でフラグメントを切り替える。
 *
 * 対応パーミッション:
 *  - RECORD_AUDIO          : いびき録音
 *  - READ_MEDIA_AUDIO      : 音楽ファイル選択（Android 13+）
 *  - READ_EXTERNAL_STORAGE : 音楽ファイル選択（Android 12以下）
 *  - POST_NOTIFICATIONS    : アラーム通知（Android 13+）
 *  - SCHEDULE_EXACT_ALARM  : 正確なアラーム（Android 12+、設定画面へ誘導）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    // ─────────────────────────────────────────────
    //  パーミッション定数
    // ─────────────────────────────────────────────

    companion object {
        /** 一度に要求するパーミッションをまとめたリスト（OS バージョンで分岐） */
        fun requiredPermissions(): Array<String> {
            val list = mutableListOf(
                Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13（API 33）以上
                list += Manifest.permission.READ_MEDIA_AUDIO
                list += Manifest.permission.POST_NOTIFICATIONS
            } else {
                // Android 12（API 32）以下
                list += Manifest.permission.READ_EXTERNAL_STORAGE
            }
            return list.toTypedArray()
        }
    }

    // ─────────────────────────────────────────────
    //  ActivityResultLauncher
    // ─────────────────────────────────────────────

    /**
     * 複数パーミッションを一括要求するランチャー。
     * 結果は Map<パーミッション名, 許可されたか> で返る。
     */
    private val multiplePermissionsLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            handlePermissionResults(results)
        }

    /**
     * SCHEDULE_EXACT_ALARM の特別設定画面から戻ってきたとき用ランチャー。
     * （この権限は requestPermissions では要求できず、設定画面へ誘導する必要がある）
     */
    private val exactAlarmSettingLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 設定から戻ってきたら状態を再チェック
            checkExactAlarmPermission()
        }

    // ─────────────────────────────────────────────
    //  ライフサイクル
    // ─────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ナビゲーションの設定
        setupNavigation()

        // 通知チャンネルの初期化（Android 8.0以上で必須）
        NotificationHelper.createNotificationChannels(this)

        // ① まず通常のランタイムパーミッションを要求
        requestRuntimePermissions()
    }

    // ─────────────────────────────────────────────
    //  ナビゲーション設定
    // ─────────────────────────────────────────────

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        binding.bottomNavigation.setupWithNavController(navController)
    }

    // ─────────────────────────────────────────────
    //  パーミッション要求フロー
    // ─────────────────────────────────────────────

    /**
     * ステップ1: 通常のランタイムパーミッション（マイク・ストレージ・通知）を要求。
     * まだ許可されていないものだけを絞り込んで要求する。
     */
    private fun requestRuntimePermissions() {
        val permissionsToRequest = requiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        when {
            permissionsToRequest.isEmpty() -> {
                // 全パーミッション取得済み → 次のステップへ
                checkExactAlarmPermission()
            }
            permissionsToRequest.any { shouldShowRequestPermissionRationale(it) } -> {
                // 一度拒否されている → 理由を説明してから再要求
                showPermissionRationaleDialog(permissionsToRequest)
            }
            else -> {
                // 初回要求
                multiplePermissionsLauncher.launch(permissionsToRequest)
            }
        }
    }

    /**
     * ステップ2: SCHEDULE_EXACT_ALARM のチェック。
     * Android 12（API 31）以上では、正確なアラームに特別な権限が必要。
     * この権限は requestPermissions で要求できないため、設定画面へ誘導する。
     */
    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                showExactAlarmPermissionDialog()
            }
            // 許可済みの場合は何もしない（アラーム設定は AlarmFragment で行う）
        }
        // Android 11以下は自動的に許可されているので何もしない
    }

    /**
     * パーミッション結果の処理。
     * 拒否された権限があった場合は警告トーストを表示し、
     * その後 SCHEDULE_EXACT_ALARM のチェックへ進む。
     */
    private fun handlePermissionResults(results: Map<String, Boolean>) {
        val deniedPermissions = results.filterValues { !it }.keys

        if (deniedPermissions.isNotEmpty()) {
            val deniedMessages = deniedPermissions.joinToString("\n") { permission ->
                "・${permissionDisplayName(permission)}"
            }
            // 拒否された権限ごとに機能制限を説明
            AlertDialog.Builder(this)
                .setTitle("一部の権限が拒否されました")
                .setMessage(
                    "以下の権限が許可されていないため、関連機能が制限されます:\n\n" +
                    "$deniedMessages\n\n" +
                    "後から設定アプリの「権限」から許可することもできます。"
                )
                .setPositiveButton("設定を開く") { _, _ ->
                    openAppSettings()
                }
                .setNegativeButton("後で") { _, _ ->
                    checkExactAlarmPermission()
                }
                .show()
        } else {
            // 全許可 → SCHEDULE_EXACT_ALARM のチェックへ
            checkExactAlarmPermission()
        }
    }

    // ─────────────────────────────────────────────
    //  ダイアログ
    // ─────────────────────────────────────────────

    /**
     * パーミッション要求前に理由を説明するダイアログ。
     * ユーザーが一度「拒否」した後に再度要求する際に表示。
     */
    private fun showPermissionRationaleDialog(permissions: Array<String>) {
        val featureDescriptions = permissions.joinToString("\n") { permission ->
            "・${permissionDisplayName(permission)}: ${permissionRationale(permission)}"
        }

        AlertDialog.Builder(this)
            .setTitle("アプリの使用に権限が必要です")
            .setMessage(
                "熟睡アラームは以下の権限を使用します:\n\n" +
                "$featureDescriptions\n\n" +
                "これらの権限はアプリの主要機能に必要です。"
            )
            .setPositiveButton("権限を許可する") { _, _ ->
                multiplePermissionsLauncher.launch(permissions)
            }
            .setNegativeButton("キャンセル") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "一部の機能が使用できません", Toast.LENGTH_SHORT).show()
                checkExactAlarmPermission()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * SCHEDULE_EXACT_ALARM の説明ダイアログ。
     * この権限は設定画面経由でしか許可できないため、設定画面へ誘導する。
     */
    private fun showExactAlarmPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("正確なアラーム権限が必要です")
            .setMessage(
                "スマートアラームを正確な時刻に鳴らすには、\n" +
                "「正確なアラームと通知の設定」の許可が必要です。\n\n" +
                "次の設定画面で「熟睡アラーム」を許可してください。"
            )
            .setPositiveButton("設定を開く") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    exactAlarmSettingLauncher.launch(intent)
                }
            }
            .setNegativeButton("後で") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(
                    this,
                    "正確なアラーム権限がないと、アラームが数分ずれる可能性があります",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setCancelable(false)
            .show()
    }

    // ─────────────────────────────────────────────
    //  ユーティリティ
    // ─────────────────────────────────────────────

    /**
     * アプリの設定画面を開く（「権限」タブから手動で許可してもらう）
     */
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    /**
     * 権限の表示名（ダイアログ用）
     */
    private fun permissionDisplayName(permission: String): String = when (permission) {
        Manifest.permission.RECORD_AUDIO         -> "マイク（いびき録音）"
        Manifest.permission.READ_MEDIA_AUDIO     -> "音楽ファイル（アラーム音選択）"
        Manifest.permission.READ_EXTERNAL_STORAGE -> "ストレージ（アラーム音選択）"
        Manifest.permission.POST_NOTIFICATIONS   -> "通知（アラーム・計測通知）"
        else                                     -> permission.substringAfterLast('.')
    }

    /**
     * 権限が必要な理由（ダイアログ用）
     */
    private fun permissionRationale(permission: String): String = when (permission) {
        Manifest.permission.RECORD_AUDIO         -> "いびきを録音し、起床後に確認できます"
        Manifest.permission.READ_MEDIA_AUDIO     -> "好きな音楽をアラーム音に設定できます"
        Manifest.permission.READ_EXTERNAL_STORAGE -> "好きな音楽をアラーム音に設定できます"
        Manifest.permission.POST_NOTIFICATIONS   -> "アラームと計測中の通知を表示します"
        else                                     -> "アプリの機能に使用します"
    }

    /**
     * 計測サービスが実行中かどうかを確認（UIからの状態同期用）
     */
    fun isSleepMonitorRunning(): Boolean {
        return SleepMonitorService.isRunning
    }
}
