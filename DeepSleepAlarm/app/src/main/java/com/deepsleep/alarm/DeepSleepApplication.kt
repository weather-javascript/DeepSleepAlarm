package com.deepsleep.alarm

import android.app.Application
import com.deepsleep.alarm.util.NotificationHelper

/**
 * DeepSleepApplication
 *
 * アプリケーションクラス。
 * - 通知チャンネルの初期化（Android 8.0以上で必須）
 *
 * AndroidManifest.xml の <application android:name=".DeepSleepApplication"> で指定する。
 */
class DeepSleepApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 通知チャンネルを作成（一度作れば以降は何もしない・冪等）
        NotificationHelper.createNotificationChannels(this)
    }
}
