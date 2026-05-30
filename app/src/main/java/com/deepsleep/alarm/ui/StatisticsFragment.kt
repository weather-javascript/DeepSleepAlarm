package com.deepsleep.alarm.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.deepsleep.alarm.R
import com.deepsleep.alarm.data.model.SleepDataPoint
import com.deepsleep.alarm.data.repository.SleepRepository
import com.deepsleep.alarm.databinding.FragmentStatisticsBinding
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * StatisticsFragment
 *
 * 睡眠統計表示画面。
 * - 睡眠深度グラフ（MPAndroidChart の LineChart）
 * - 就床時間・睡眠時間・睡眠効率・いびき回数などの統計
 * - 直近セッションの録音リスト（タップで再生）
 */
class StatisticsFragment : Fragment() {

    private var _binding: FragmentStatisticsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: SleepRepository

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatisticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = SleepRepository(requireContext())
        setupSleepDepthChart()
        loadLatestSessionStats()
    }

    // ─────────────────────────────────────────────
    //  睡眠深度グラフの初期設定
    // ─────────────────────────────────────────────

    private fun setupSleepDepthChart() {
        binding.sleepDepthChart.apply {
            description.isEnabled = false
            legend.isEnabled = true
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setNoDataText("睡眠データがありません")

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                valueFormatter = TimeAxisValueFormatter()
                labelRotationAngle = -45f
            }

            axisLeft.apply {
                setDrawGridLines(true)
                axisMinimum = 0f
                axisMaximum = 1f
                // Y軸ラベルを「深い」「浅い」に
                valueFormatter = SleepDepthValueFormatter()
            }

            axisRight.isEnabled = false
        }
    }

    // ─────────────────────────────────────────────
    //  最新セッション統計の読み込み
    // ─────────────────────────────────────────────

    private fun loadLatestSessionStats() {
        lifecycleScope.launch {
            val latestSession = repository.getLatestSession() ?: return@launch
            val sessionId = latestSession.id

            // 統計サマリーを計算・表示
            val stats = repository.calcSessionStats(sessionId)
            displayStats(stats)

            // データポイントをグラフに反映
            repository.getDataPointsFlow(sessionId).collectLatest { dataPoints ->
                updateChart(dataPoints)
            }
        }
    }

    private fun displayStats(stats: com.deepsleep.alarm.data.repository.SleepStatsSummary) {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.JAPAN)
        val durationFormat = { ms: Long ->
            val hours   = TimeUnit.MILLISECONDS.toHours(ms)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
            "${hours}時間${minutes}分"
        }

        binding.apply {
            tvBedTime.text  = if (stats.bedTimeMs > 0) timeFormat.format(Date(stats.bedTimeMs)) else "--:--"
            tvWakeTime.text = if (stats.wakeTimeMs > 0) timeFormat.format(Date(stats.wakeTimeMs)) else "--:--"
            tvSleepDuration.text = if (stats.totalSleepMs > 0) durationFormat(stats.totalSleepMs) else "--"
            tvSleepEfficiency.text = "${(stats.efficiency * 100).toInt()}%"
            tvDeepSleepRatio.text  = "${(stats.deepSleepRatio * 100).toInt()}%"
            tvLightSleepRatio.text = "${(stats.lightSleepRatio * 100).toInt()}%"
        }
    }

    // ─────────────────────────────────────────────
    //  グラフ更新
    // ─────────────────────────────────────────────

    private fun updateChart(dataPoints: List<SleepDataPoint>) {
        if (dataPoints.isEmpty()) return

        val startTimeMs = dataPoints.first().timestampMs

        // 睡眠深度ラインデータセット
        val sleepEntries = dataPoints.mapIndexed { _, point ->
            val xMin = (point.timestampMs - startTimeMs) / 60_000f  // 経過分
            // グラフは「下=深い」なので反転（1.0-depth）
            Entry(xMin, 1f - point.sleepDepth)
        }

        val sleepDataSet = LineDataSet(sleepEntries, "睡眠深度").apply {
            color = requireContext().getColor(R.color.sleep_depth_line)
            setDrawCircles(false)
            lineWidth = 2f
            fillAlpha = 80
            setDrawFilled(true)
            fillColor = requireContext().getColor(R.color.sleep_depth_fill)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        // いびきデータセット（散布図的に）
        val snoreEntries = dataPoints
            .filter { it.snoreDb >= 60.0 }
            .map { point ->
                val xMin = (point.timestampMs - startTimeMs) / 60_000f
                Entry(xMin, 1f - point.sleepDepth)
            }

        val snoreDataSet = LineDataSet(snoreEntries, "いびき").apply {
            color = requireContext().getColor(R.color.snore_indicator)
            setDrawCircles(true)
            circleRadius = 4f
            setCircleColor(requireContext().getColor(R.color.snore_indicator))
            lineWidth = 0f
            setDrawValues(false)
        }

        binding.sleepDepthChart.data = LineData(sleepDataSet, snoreDataSet)
        binding.sleepDepthChart.invalidate()
    }

    // ─────────────────────────────────────────────
    //  カスタム軸フォーマッター
    // ─────────────────────────────────────────────

    inner class TimeAxisValueFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            val hours   = (value / 60).toInt()
            val minutes = (value % 60).toInt()
            return "%d:%02d".format(hours, minutes)
        }
    }

    inner class SleepDepthValueFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String = when {
            value >= 0.8f -> "覚醒"
            value >= 0.5f -> "浅い"
            value >= 0.2f -> "普通"
            else          -> "深い"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
