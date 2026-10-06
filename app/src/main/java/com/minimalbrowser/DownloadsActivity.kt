package com.minimalbrowser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.minimalbrowser.databinding.ActivityDownloadsBinding
import com.minimalbrowser.databinding.ItemDownloadBinding
import java.util.Locale

class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var repo: DownloadRepository
    private lateinit var adapter: DownloadAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            val delay = if (adapter.anyActive()) 1000L else 3000L
            handler.postDelayed(this, delay)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = DownloadRepository(this)
        adapter = DownloadAdapter { item, anchor -> showRowMenu(item, anchor) }

        binding.downloadList.layoutManager = LinearLayoutManager(this)
        binding.downloadList.adapter = adapter
        binding.downloadList.setHasFixedSize(false)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClear.setOnClickListener { clearCompleted() }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun refresh() {
        val items = repo.listAll()
        adapter.submit(items)
        val empty = items.isEmpty()
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.downloadList.visibility = if (empty) View.GONE else View.VISIBLE
        binding.btnClear.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun clearCompleted() {
        val n = repo.clearCompleted()
        if (n <= 0) {
            Toast.makeText(this, R.string.downloads_nothing_to_clear, Toast.LENGTH_SHORT).show()
        } else {
            refresh()
        }
    }

    // ----------------------------------------------------------------
    // Per-row menu
    // ----------------------------------------------------------------

    private fun showRowMenu(item: DownloadRepository.Item, anchor: View) {
        val menu = PopupMenu(this, anchor)
        val canPause = repo.supportsPauseResume

        when {
            item.isLocal -> {
                menu.menu.add(0, MENU_OPEN, 0, R.string.downloads_action_open)
                menu.menu.add(0, MENU_SHARE, 1, R.string.downloads_action_share)
                menu.menu.add(0, MENU_DELETE, 2, R.string.downloads_action_delete)
            }
            item.isRunning -> {
                if (canPause) {
                    menu.menu.add(0, MENU_PAUSE, 0, R.string.downloads_action_pause)
                    menu.menu.add(0, MENU_CANCEL, 1, R.string.downloads_action_cancel)
                } else {
                    menu.menu.add(0, MENU_CANCEL, 0, R.string.downloads_action_cancel)
                }
            }
            item.isPaused -> {
                if (canPause) {
                    menu.menu.add(0, MENU_RESUME, 0, R.string.downloads_action_resume)
                    menu.menu.add(0, MENU_CANCEL, 1, R.string.downloads_action_cancel)
                } else {
                    menu.menu.add(0, MENU_CANCEL, 0, R.string.downloads_action_cancel)
                }
            }
            item.isSuccess -> {
                menu.menu.add(0, MENU_OPEN, 0, R.string.downloads_action_open)
                menu.menu.add(0, MENU_SHARE, 1, R.string.downloads_action_share)
                menu.menu.add(0, MENU_SYSTEM, 2, R.string.downloads_action_system)
                menu.menu.add(0, MENU_DELETE, 3, R.string.downloads_action_delete)
            }
            item.isFailed -> {
                menu.menu.add(0, MENU_RETRY, 0, R.string.downloads_action_retry)
                menu.menu.add(0, MENU_SYSTEM, 1, R.string.downloads_action_system)
                menu.menu.add(0, MENU_DELETE, 2, R.string.downloads_action_delete)
            }
            else -> {
                menu.menu.add(0, MENU_CANCEL, 0, R.string.downloads_action_cancel)
            }
        }

        menu.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                MENU_PAUSE -> { repo.pause(item); refresh(); true }
                MENU_RESUME -> { repo.resume(item); refresh(); true }
                MENU_CANCEL -> { repo.cancel(item); refresh(); true }
                MENU_RETRY -> {
                    if (repo.retry(item) == null) {
                        Toast.makeText(this, R.string.downloads_retry_failed, Toast.LENGTH_SHORT).show()
                    }
                    refresh()
                    true
                }
                MENU_OPEN -> { openItem(item); true }
                MENU_SHARE -> { shareItem(item); true }
                MENU_SYSTEM -> { openSystemDownloads(); true }
                MENU_DELETE -> { confirmDelete(item); true }
                else -> false
            }
        }
        menu.show()
    }

    private fun openItem(item: DownloadRepository.Item) {
        val uri = repo.fileUriFor(item)
        if (uri == null) {
            Toast.makeText(this, R.string.downloads_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val mime = item.mimeType.ifBlank { "*/*" }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) }
        catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.downloads_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareItem(item: DownloadRepository.Item) {
        val uri = repo.fileUriFor(item)
        if (uri == null) {
            Toast.makeText(this, R.string.downloads_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val mime = item.mimeType.ifBlank { "*/*" }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.downloads_action_share)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.downloads_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSystemDownloads() {
        try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
        catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.download_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(item: DownloadRepository.Item) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.downloads_delete_confirm_title)
            .setMessage(getString(R.string.downloads_delete_confirm_message, item.fileName))
            .setPositiveButton(R.string.downloads_delete_confirm_yes) { _, _ ->
                if (!repo.deleteFile(item)) {
                    Toast.makeText(this, R.string.downloads_delete_failed, Toast.LENGTH_SHORT).show()
                }
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ----------------------------------------------------------------
    // Adapter
    // ----------------------------------------------------------------

    private inner class DownloadAdapter(
        private val onMenu: (DownloadRepository.Item, View) -> Unit
    ) : RecyclerView.Adapter<DownloadAdapter.VH>() {

        private val items = ArrayList<DownloadRepository.Item>()

        fun submit(newItems: List<DownloadRepository.Item>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun anyActive(): Boolean = items.any { it.isRunning }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemDownloadBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class VH(val b: ItemDownloadBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: DownloadRepository.Item) {
                b.rowName.text = item.fileName
                b.rowStatus.text = statusLine(item)
                if (item.isRunning && item.progressPercent >= 0) {
                    b.rowProgress.visibility = View.VISIBLE
                    b.rowProgress.setProgressCompat(item.progressPercent, true)
                } else {
                    b.rowProgress.visibility = View.GONE
                }
                b.rowMenu.setOnClickListener { v -> onMenu(item, v) }
                b.root.setOnClickListener {
                    if (item.isSuccess) onMenu(item, b.rowMenu)
                }
            }
        }
    }

    // ----------------------------------------------------------------
    // Formatting
    // ----------------------------------------------------------------

    private fun statusLine(item: DownloadRepository.Item): String {
        val sizeText: String = when {
            item.totalBytes > 0L ->
                formatBytes(item.bytesDownloaded) + " / " + formatBytes(item.totalBytes)
            item.bytesDownloaded > 0L -> formatBytes(item.bytesDownloaded)
            else -> ""
        }

        return when {
            item.isSuccess -> {
                val sz = if (item.totalBytes > 0L) formatBytes(item.totalBytes) else ""
                if (sz.isNotEmpty()) {
                    getString(
                        R.string.downloads_status_completed_with_size,
                        sz,
                        humanAge(item.lastModified)
                    )
                } else getString(R.string.downloads_status_completed)
            }
            item.isRunning -> {
                val pct = item.progressPercent
                if (pct >= 0 && sizeText.isNotEmpty()) {
                    getString(
                        R.string.downloads_status_running_with_progress,
                        pct.toString(),
                        sizeText
                    )
                } else getString(R.string.downloads_status_running)
            }
            item.isPaused -> getString(R.string.downloads_status_paused)
            item.isFailed -> getString(
                R.string.downloads_status_failed,
                reasonText(item.reason)
            )
            else -> getString(R.string.downloads_status_queued)
        }
    }

    private fun reasonText(reason: Int): String = when (reason) {
        DownloadManager.ERROR_CANNOT_RESUME -> "cannot resume"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "storage unavailable"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "file exists"
        DownloadManager.ERROR_FILE_ERROR -> "file error"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "network error"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "no space"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "server error"
        DownloadManager.ERROR_UNKNOWN -> "unknown"
        else -> "error " + reason
    }

    private fun formatBytes(b: Long): String {
        if (b < 1024L) return b.toString() + " B"
        val kb = b / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    private fun humanAge(ms: Long): String {
        if (ms <= 0L) return "just now"
        val diff = System.currentTimeMillis() - ms
        if (diff < 0L) return "just now"
        val mins = diff / 60_000L
        if (mins < 1L) return "just now"
        if (mins < 60L) return mins.toString() + "m ago"
        val hours = mins / 60L
        if (hours < 24L) return hours.toString() + "h ago"
        val days = hours / 24L
        return days.toString() + "d ago"
    }

    private companion object {
        const val MENU_PAUSE = 1
        const val MENU_RESUME = 2
        const val MENU_CANCEL = 3
        const val MENU_RETRY = 4
        const val MENU_OPEN = 5
        const val MENU_SHARE = 6
        const val MENU_DELETE = 7
        const val MENU_SYSTEM = 8
    }
}
