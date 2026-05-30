# 熟睡アラーム (DeepSleepAlarm)

Android用スマートアラームアプリ。加速度センサーとマイクを使って睡眠を計測し、眠りが浅いタイミング（レム睡眠）を狙ってアラームを鳴らします。

## 主な機能

| 機能 | 説明 |
|------|------|
| スマートアラーム | 設定時刻の最大60分前から眠りが浅い瞬間を検知して起こす |
| 睡眠深度計測 | 加速度センサーで体動を検出し、睡眠の深さをリアルタイム記録 |
| いびき検知・録音 | マイクで音量を計測し、60dB以上を「いびき」として録音保存 |
| 睡眠統計 | 就床時間・睡眠効率・深い睡眠の割合などをグラフで可視化 |

## 動作環境

- **対応OS**: Android 8.0 (API 26) 以上
- **推奨OS**: Android 13 (API 33) 以上
- **必要センサー**: 加速度センサー・マイク

---

## プロジェクト構成

```
DeepSleepAlarm/
├── .github/workflows/
│   └── build.yml                   # GitHub Actions CI/CD（APKビルド自動化）
├── app/
│   ├── build.gradle                # モジュール依存関係
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml     # 全パーミッション・Service宣言
│       └── java/com/deepsleep/alarm/
│           ├── DeepSleepApplication.kt      # Application クラス
│           ├── ui/
│           │   ├── MainActivity.kt          # パーミッション要求・ナビゲーション
│           │   ├── AlarmFragment.kt         # アラーム設定画面
│           │   ├── StatisticsFragment.kt    # 睡眠統計・グラフ画面
│           │   └── AlarmRingActivity.kt     # アラーム発火画面（ロック画面対応）
│           ├── service/
│           │   ├── SleepMonitorService.kt   # フォアグラウンドサービス（計測コア）
│           │   ├── AlarmScheduler.kt        # AlarmManager ラッパー
│           │   ├── AlarmReceiver.kt         # アラーム受信 BroadcastReceiver
│           │   └── BootReceiver.kt          # 再起動後アラーム復元
│           ├── data/
│           │   ├── model/
│           │   │   ├── Entities.kt          # Room エンティティ定義
│           │   │   └── Daos.kt              # Room DAO インターフェース
│           │   └── repository/
│           │       └── SleepRepository.kt   # DB + 統計計算
│           └── util/
│               └── NotificationHelper.kt    # 通知チャンネル管理
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## パーミッション詳細

| パーミッション | 用途 | 取得タイミング |
|---|---|---|
| `RECORD_AUDIO` | いびき録音 | アプリ起動時（ランタイム） |
| `READ_MEDIA_AUDIO` (API 33+) | 音楽ファイル選択 | アプリ起動時（ランタイム） |
| `READ_EXTERNAL_STORAGE` (API ≤32) | 音楽ファイル選択 | アプリ起動時（ランタイム） |
| `POST_NOTIFICATIONS` (API 33+) | アラーム通知表示 | アプリ起動時（ランタイム） |
| `SCHEDULE_EXACT_ALARM` | 正確なアラーム | 設定画面へ誘導（特別権限） |
| `FOREGROUND_SERVICE` | バックグラウンド計測 | インストール時（自動付与） |
| `WAKE_LOCK` | CPU スリープ防止 | インストール時（自動付与） |
| `RECEIVE_BOOT_COMPLETED` | 再起動後アラーム復元 | インストール時（自動付与） |

---

## GitHub Actions によるビルド

### デバッグ APK（自動）
`main` または `develop` へのプッシュで自動ビルド。
GitHub の **Actions** タブ → **Artifacts** からダウンロードできます。

### リリース APK（署名付き）
`v1.0.0` のようなタグをプッシュするとリリースビルドが実行され、
GitHub Releases に APK が自動アップロードされます。

#### 必要な GitHub Secrets の設定

| Secret 名 | 内容 |
|---|---|
| `KEYSTORE_FILE` | キーストアファイルの Base64 エンコード済み文字列 |
| `KEYSTORE_PASSWORD` | キーストアのパスワード |
| `KEY_ALIAS` | キーのエイリアス名 |
| `KEY_PASSWORD` | キーのパスワード |

**キーストアのBase64変換方法:**
```bash
base64 -i your-release-key.jks | pbcopy  # macOS
base64 your-release-key.jks | xclip      # Linux
```

---

## ローカルビルド手順

```bash
# クローン
git clone https://github.com/yourname/DeepSleepAlarm.git
cd DeepSleepAlarm

# デバッグ APK をビルド
./gradlew assembleDebug

# 接続済みデバイスにインストール
./gradlew installDebug

# Lint チェック
./gradlew lint
```

出力先: `app/build/outputs/apk/debug/app-debug.apk`

---

## 技術スタック

- **言語**: Kotlin
- **アーキテクチャ**: MVVM + Repository パターン
- **データベース**: Room (SQLite)
- **音楽再生**: ExoPlayer (Media3)
- **グラフ**: MPAndroidChart
- **バックグラウンド**: Foreground Service + WakeLock
- **非同期処理**: Kotlin Coroutines + Flow
- **通知**: NotificationCompat + NotificationChannel

---

## 注意事項

- **いびき録音ファイル**はアプリの内部ストレージに保存されます（`/data/data/com.deepsleep.alarm/files/snore_recordings/`）。アプリをアンインストールすると削除されます。
- スマートアラームの精度は端末の加速度センサーの感度と枕の素材に依存します。枕元に端末を平置きにして使用してください。
- バッテリー最適化を無効にすることで、バックグラウンド動作の信頼性が向上します（`設定 → バッテリー → バッテリーの最適化 → 熟睡アラーム → 最適化しない`）。
