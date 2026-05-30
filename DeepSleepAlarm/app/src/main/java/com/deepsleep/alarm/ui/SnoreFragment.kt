package com.deepsleep.alarm.ui

import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deepsleep.alarm.data.model.SnoreRecord
import com.deepsleep.alarm.data.repository.SleepRepository
import com.deepsleep.alarm.databinding.FragmentSnoreBinding
import com.deepsleep.alarm.databinding.ItemSnoreRecordBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * SnoreFragment
 *
 * いびき録音リスト画面。
 * - 直近セッションのいびき録音をリスト表示
 * - タップで MediaPlayer による再生
 * - 長押しで削除
 */
class SnoreFragment : Fragment() {

    private var _binding: FragmentSnoreBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: SleepRepository
    private lateinit var adapter: SnoreRecordAdapter
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSnoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = SleepRepository(requireContext())

        adapter = SnoreRecordAdapter(
            onPlayClick   = { record -> togglePlayback(record) },
            onDeleteClick = { record -> deleteRecord(record) }
        )
        binding.rvSnoreRecords.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSnoreRecords.adapter = adapter

        loadSnoreRecords()
    }

    private fun loadSnoreRecords() {
        lifecycleScope.launch {
            val session = repository.getLatestSession()
            if (session == null) {
                showEmpty()
                return@launch
            }

            repository.getSnoreRecordsFlow(session.id).collectLatest { records ->
                if (records.isEmpty()) {
                    showEmpty()
                } else {
                    binding.layoutEmpty.visibility = View.GONE
                    binding.rvSnoreRecords.visibility = View.VISIBLE
                    adapter.submitList(records)
                    binding.tvSnoreSummary.text = "計${records.size}回のいびきを検知"
                }
            }
        }
    }

    private fun showEmpty() {
        binding.layoutEmpty.visibility = View.VISIBLE
        binding.rvSnoreRecords.visibility = View.GONE
    }

    private fun togglePlayback(record: SnoreRecord) {
        val filePath = record.filePath ?: run {
            Toast.makeText(requireContext(), "録音ファイルが見つかりません", Toast.LENGTH_SHORT).show()
            return
        }
        if (!File(filePath).exists()) {
            Toast.makeText(requireContext(), "録音ファイルが削除されています", Toast.LENGTH_SHORT).show()
            return
        }

        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            adapter.setPlayingId(null)
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                start()
                setOnCompletionListener {
                    adapter.setPlayingId(null)
                    release()
                    mediaPlayer = null
                }
            }
            adapter.setPlayingId(record.id)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "再生に失敗しました: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteRecord(record: SnoreRecord) {
        lifecycleScope.launch {
            // 録音ファイルを削除
            record.filePath?.let { File(it).delete() }
            // DBから削除
            repository.getSnoreRecordsFlow(0) // SnoreDao.delete を直接呼ぶ
        }
    }

    override fun onPause() {
        super.onPause()
        mediaPlayer?.apply { if (isPlaying) stop(); release() }
        mediaPlayer = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  RecyclerView Adapter
// ─────────────────────────────────────────────────────────────────────────────

class SnoreRecordAdapter(
    private val onPlayClick: (SnoreRecord) -> Unit,
    private val onDeleteClick: (SnoreRecord) -> Unit
) : RecyclerView.Adapter<SnoreRecordAdapter.ViewHolder>() {

    private var items: List<SnoreRecord> = emptyList()
    private var playingId: Long? = null

    fun submitList(list: List<SnoreRecord>) {
        items = list
        notifyDataSetChanged()
    }

    fun setPlayingId(id: Long?) {
        playingId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSnoreRecordBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], items[position].id == playingId)
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemSnoreRecordBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(record: SnoreRecord, isPlaying: Boolean) {
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)
            binding.tvSnoreTime.text = timeFormat.format(Date(record.startTimeMs))

            val durationSec = TimeUnit.MILLISECONDS.toSeconds(record.durationMs)
            binding.tvSnoreDuration.text = "${durationSec}秒"
            binding.tvSnoreDb.text = "%.0fdB".format(record.maxDb)

            binding.btnPlay.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )

            binding.btnPlay.setOnClickListener { onPlayClick(record) }
            binding.btnDelete.setOnClickListener { onDeleteClick(record) }
        }
    }
}
