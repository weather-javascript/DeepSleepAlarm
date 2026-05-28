# Room データベース
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }

# ExoPlayer / Media3
-keepclassmembers class androidx.media3.** { *; }

# アプリ固有のエンティティ（Room コンパイラが生成するクラスを保護）
-keep class com.deepsleep.alarm.data.model.** { *; }
