package com.minimalbrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.minimalbrowser.databinding.ActivityHistoryBinding
import com.minimalbrowser.databinding.ItemHistoryEntryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    enum class SortMode { RECENT, ALPHABETICAL, MOST_VISITED }

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var store: HistoryStore
    private lateinit var adapter: HistoryAdapter

    private var sortMode = SortMode.RECENT
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = HistoryStore.get(this)
        adapter = HistoryAdapter { entry, anchor -> showEntryMenu(entry, anchor) }

        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSort.setOnClickListener { showSortMenu(it) }
        binding.btnClearAll.setOnClickListener { showClearMenu(it) }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty().trim().lowercase()
                refresh()
            }
            override fun beforeTextChanged(
                s: CharSequence?, st: Int, c: Int, a: Int
            ) {}
            override fun onTextChanged(
                s: CharSequence?, st: Int, b: Int, c: Int
            ) {}
        })

        binding.searchInput.setOnEditorActionListener { _, actionId, event ->
            val isSearch = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN)
            if (isSearch) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? InputMethodManager
                imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
                true
            } else false
        }

        updateSortLabel()
        refresh()
    }

    private fun refresh() {
        val all = store.loadAll()

        val filtered = if (query.isEmpty()) all
        else all.filter { e ->
            e.url.lowercase().contains(query) ||
                e.title.lowercase().contains(query)
        }

        val sorted = when (sortMode) {
            SortMode.RECENT -> filtered.sortedByDescending { it.lastVisited }
            SortMode.ALPHABETICAL -> filtered.sortedBy { e ->
                (if (e.title.isBlank()) e.url else e.title).lowercase()
            }
            SortMode.MOST_VISITED -> filtered.sortedByDescending { it.visitCount }
        }

        adapter.submit(sorted)

        val empty = sorted.isEmpty()
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.historyList.visibility = if (empty) View.GONE else View.VISIBLE
        binding.btnClearAll.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyText.text = if (all.isEmpty()) {
            getString(R.string.history_empty_title)
        } else {
            getString(R.string.history_no_results)
        }
    }

    private fun updateSortLabel() {
        binding.btnSort.contentDescription = when (sortMode) {
            SortMode.RECENT -> getString(R.string.history_sort_recent)
            SortMode.ALPHABETICAL -> getString(R.string.history_sort_az)
            SortMode.MOST_VISITED -> getString(R.string.history_sort_most_visited)
        }
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, SortMode.RECENT.ordinal, 0, R.string.history_sort_recent)
        popup.menu.add(0, SortMode.ALPHABETICAL.ordinal, 1, R.string.history_sort_az)
        popup.menu.add(0, SortMode.MOST_VISITED.ordinal, 2, R.string.history_sort_most_visited)
        popup.setOnMenuItemClickListener { item ->
            val newMode = when (item.itemId) {
                SortMode.RECENT.ordinal -> SortMode.RECENT
                SortMode.ALPHABETICAL.ordinal -> SortMode.ALPHABETICAL
                SortMode.MOST_VISITED.ordinal -> SortMode.MOST_VISITED
                else -> return@setOnMenuItemClickListener false
            }
            sortMode = newMode
            updateSortLabel()
            refresh()
            true
        }
        popup.show()
    }

    private fun showEntryMenu(entry: HistoryStore.Entry, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_OPEN, 0, R.string.history_action_open)
        popup.menu.add(0, MENU_OPEN_NEW_TAB, 1, R.string.history_action_open_new_tab)
        popup.menu.add(0, MENU_COPY, 2, R.string.history_action_copy)
        popup.menu.add(0, MENU_DELETE, 3, R.string.history_action_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_OPEN -> { openEntry(entry); true }
                MENU_OPEN_NEW_TAB -> { openInNewTab(entry); true }
                MENU_COPY -> { copyUrl(entry); true }
                MENU_DELETE -> { deleteEntry(entry); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun openEntry(entry: HistoryStore.Entry) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(MainActivity.EXTRA_SHORTCUT_URL, entry.url)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
        )
        finish()
    }

    private fun openInNewTab(entry: HistoryStore.Entry) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(entry.url)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
        )
        finish()
    }

    private fun copyUrl(entry: HistoryStore.Entry) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", entry.url))
        Toast.makeText(this, R.string.history_url_copied, Toast.LENGTH_SHORT).show()
    }

    private fun deleteEntry(entry: HistoryStore.Entry) {
        store.remove(entry.url)
        refresh()
    }

    // ------------------------------------------------------------------
    // Clear menu
    // ------------------------------------------------------------------

    private fun showClearMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, CLEAR_ALL, 0, R.string.history_clear_all)
        popup.menu.add(0, CLEAR_OLDER, 1, R.string.history_clear_older_30d)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                CLEAR_ALL -> { confirmClearAll(); true }
                CLEAR_OLDER -> { confirmClearOlder(30); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_clear_confirm_title)
            .setMessage(R.string.history_clear_confirm_message)
            .setPositiveButton(R.string.history_clear_confirm_yes) { _, _ ->
                store.clear()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClearOlder(days: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_clear_older_confirm_title)
            .setMessage(getString(R.string.history_clear_older_confirm_message, days))
            .setPositiveButton(R.string.history_clear_confirm_yes) { _, _ ->
                val n = store.clearOlderThan(days)
                if (n == 0) {
                    Toast.makeText(
                        this,
                        R.string.history_clear_older_nothing,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    private inner class HistoryAdapter(
        private val onMenu: (HistoryStore.Entry, View) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        private val items = ArrayList<HistoryStore.Entry>()

        fun submit(newItems: List<HistoryStore.Entry>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemHistoryEntryBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position], position)
        }

        override fun getItemCount(): Int = items.size

        inner class VH(val b: ItemHistoryEntryBinding) :
            RecyclerView.ViewHolder(b.root) {

            private var loadJob: Job? = null

            fun bind(entry: HistoryStore.Entry, position: Int) {
                // Cancel any prior favicon load for this recycled view.
                loadJob?.cancel()

                // Placeholder state while the favicon loads.
                b.rowIcon.alpha = 0.65f
                b.rowIcon.imageTintList =
                    ColorStateList.valueOf(getColor(R.color.text_secondary))
                b.rowIcon.setImageResource(R.drawable.ic_history)

                loadJob = this@HistoryActivity.lifecycleScope.launch {
                    val icon = withContext(Dispatchers.IO) {
                        store.loadFavicon(entry.url)
                    }
                    // Guard against the view having been recycled to a
                    // different row during the IO hop. withContext is
                    // cancellation-aware, so a rebind that cancelled this
                    // job will not reach here at all — this check exists
                    // for the case where the coroutine completes cleanly
                    // but the adapter has since reassigned the slot.
                    if (icon != null && bindingAdapterPosition == position) {
                        b.rowIcon.imageTintList = null
                        b.rowIcon.alpha = 1.0f
                        b.rowIcon.setImageBitmap(icon)
                    }
                }

                b.rowTitle.text = entry.title.ifBlank { hostOf(entry.url) }
                b.rowUrl.text = entry.url
                b.rowTimestamp.text = relativeTime(entry.lastVisited)
                b.rowMenu.setOnClickListener { v -> onMenu(entry, v) }
                b.root.setOnClickListener { openEntry(entry) }
            }
        }
    }

    private fun hostOf(url: String): String = runCatching {
        java.net.URI(url).host?.removePrefix("www.").orEmpty()
    }.getOrDefault(url)

    private fun relativeTime(ms: Long): String {
        if (ms <= 0L) return getString(R.string.history_time_unknown)
        val diff = System.currentTimeMillis() - ms
        if (diff < 0L) return getString(R.string.history_time_just_now)
        val mins = diff / 60_000L
        if (mins < 1L) return getString(R.string.history_time_just_now)
        if (mins < 60L) return "$mins m ago"
        val hours = mins / 60L
        if (hours < 24L) return "$hours h ago"
        val days = hours / 24L
        if (days < 7L) return "$days d ago"
        return SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
    }

    private companion object {
        const val MENU_OPEN = 1
        const val MENU_OPEN_NEW_TAB = 2
        const val MENU_COPY = 3
        const val MENU_DELETE = 4

        const val CLEAR_ALL = 1
        const val CLEAR_OLDER = 2
    }
}
