package com.minimalbrowser

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.minimalbrowser.databinding.ActivityVaultBinding
import com.minimalbrowser.databinding.ItemVaultCredentialBinding

class VaultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVaultBinding
    private lateinit var store: SecureCredentialStore
    private lateinit var adapter: VaultAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVaultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = SecureCredentialStore.get(this)
        adapter = VaultAdapter { host, user -> confirmDelete(host, user) }

        binding.vaultList.layoutManager = LinearLayoutManager(this)
        binding.vaultList.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAdd.setOnClickListener { showAddDialog() }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { refresh(s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        refresh("")
    }

    private fun refresh(query: String) {
        val q = query.trim().lowercase()
        val all = ArrayList<SecureCredentialStore.Credential>()
        for (h in store.allHosts()) all.addAll(store.accountsForHost(h))
        val filtered = if (q.isEmpty()) all else all.filter {
            it.host.lowercase().contains(q) || it.username.lowercase().contains(q)
        }
        adapter.submit(filtered)
        val empty = filtered.isEmpty()
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.vaultList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun confirmDelete(host: String, user: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vault_remove_title)
            .setMessage("$user @ $host")
            .setPositiveButton(R.string.vault_remove_yes) { _, _ ->
                store.remove(host, user)
                refresh(binding.searchInput.text?.toString().orEmpty())
                Toast.makeText(
                    this, R.string.vault_remove_done, Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        val hostInput = EditText(this).apply {
            hint = getString(R.string.vault_add_host_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        container.addView(hostInput)

        val userInput = EditText(this).apply {
            hint = getString(R.string.vault_add_user_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine()
        }
        container.addView(userInput)

        val passInput = EditText(this).apply {
            hint = getString(R.string.vault_add_pass_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        container.addView(passInput)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vault_add_title)
            .setView(container)
            .setPositiveButton(R.string.vault_add_save) { _, _ ->
                val rawHost = hostInput.text?.toString()?.trim().orEmpty()
                val u = userInput.text?.toString()?.trim().orEmpty()
                val p = passInput.text?.toString().orEmpty()
                if (rawHost.isBlank() || u.isBlank() || p.isBlank()) {
                    Toast.makeText(
                        this, R.string.vault_add_missing, Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                val clean = normaliseHost(rawHost)
                if (clean.isBlank() || !clean.contains('.')) {
                    Toast.makeText(
                        this, R.string.vault_add_bad_host, Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                store.save(clean, u, p)
                refresh(binding.searchInput.text?.toString().orEmpty())
                Toast.makeText(
                    this, R.string.vault_save_saved, Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun normaliseHost(input: String): String {
        var h = input.trim().lowercase()
        if (h.contains("://")) h = h.substringAfter("://")
        h = h.substringBefore("/").substringBefore("?").substringBefore("#")
        h = h.substringBefore(":")
        h = h.removePrefix("www.")
        return h
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private inner class VaultAdapter(
        private val onDelete: (String, String) -> Unit
    ) : RecyclerView.Adapter<VaultAdapter.VH>() {
        private val items = ArrayList<SecureCredentialStore.Credential>()
        fun submit(newItems: List<SecureCredentialStore.Credential>) {
            items.clear(); items.addAll(newItems); notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemVaultCredentialBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount(): Int = items.size
        inner class VH(val b: ItemVaultCredentialBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(c: SecureCredentialStore.Credential) {
                b.rowHost.text = c.host
                b.rowUser.text = c.username
                b.rowDelete.setOnClickListener { onDelete(c.host, c.username) }
            }
        }
    }
}
