package com.minimalbrowser

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
        adapter = VaultAdapter(
            onEdit = { cred -> showAddDialog(cred) },
            onDelete = { host, user -> confirmDelete(host, user) }
        )

        binding.vaultList.layoutManager = LinearLayoutManager(this)
        binding.vaultList.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAdd.setOnClickListener { showAddDialog() }
        binding.btnClearAll.setOnClickListener { confirmClearAll() }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                refresh(s?.toString().orEmpty())
            }
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
        // Show "Clear all" whenever there is at least one stored credential,
        // independent of the current filter. If you're filtering and the
        // query matches nothing, empty-state shows but "Clear all" stays
        // available because there IS data to clear.
        binding.btnClearAll.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
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

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vault_clear_confirm_title)
            .setMessage(R.string.vault_clear_confirm_message)
            .setPositiveButton(R.string.vault_clear_confirm_yes) { _, _ ->
                store.clear()
                refresh(binding.searchInput.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Add or edit dialog.
     *
     * When [existing] is non-null the dialog opens in edit mode: all three
     * fields are pre-filled and the title reads "Edit password". On save,
     * if the (host, username) key changed, the old entry is removed before
     * the new one is written — this is what makes "edit" a move instead of
     * leaving a stale duplicate row behind.
     *
     * Password visibility is user-togglable so blind typing mistakes can be
     * caught before the credential is written to the encrypted store. The
     * cursor position is preserved across the flip because reassigning
     * inputType resets the selection on some IMEs.
     */
    private fun showAddDialog(existing: SecureCredentialStore.Credential? = null) {
        val isEdit = existing != null

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        val hostInput = EditText(this).apply {
            hint = getString(R.string.vault_add_host_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            existing?.let { setText(it.host) }
        }
        container.addView(hostInput)

        val userInput = EditText(this).apply {
            hint = getString(R.string.vault_add_user_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine()
            existing?.let { setText(it.username) }
        }
        container.addView(userInput)

        val passInput = EditText(this).apply {
            hint = getString(R.string.vault_add_pass_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            existing?.let { setText(it.password) }
        }
        container.addView(passInput)

        val showPass = CheckBox(this).apply {
            text = getString(R.string.vault_show_password)
            setTextColor(getColor(R.color.text_secondary))
        }
        showPass.setOnCheckedChangeListener { _, checked ->
            val selection = passInput.selectionEnd.coerceAtLeast(0)
            passInput.inputType = if (checked) {
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            passInput.setSelection(
                selection.coerceAtMost(passInput.text?.length ?: 0)
            )
        }
        container.addView(showPass)

        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (isEdit) R.string.vault_edit_title
                else R.string.vault_add_title
            )
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
                // Editing: if the key changed, drop the old entry first so
                // the row moves rather than leaving a stale duplicate.
                existing?.let { old ->
                    if (old.host != clean || old.username != u) {
                        store.remove(old.host, old.username)
                    }
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
        private val onEdit: (SecureCredentialStore.Credential) -> Unit,
        private val onDelete: (String, String) -> Unit
    ) : RecyclerView.Adapter<VaultAdapter.VH>() {
        private val items = ArrayList<SecureCredentialStore.Credential>()
        fun submit(newItems: List<SecureCredentialStore.Credential>) {
            items.clear(); items.addAll(newItems); notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemVaultCredentialBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            ))
        override fun onBindViewHolder(holder: VH, position: Int) =
            holder.bind(items[position])
        override fun getItemCount(): Int = items.size
        inner class VH(val b: ItemVaultCredentialBinding) :
            RecyclerView.ViewHolder(b.root) {
            fun bind(c: SecureCredentialStore.Credential) {
                b.rowHost.text = c.host
                b.rowUser.text = c.username
                b.rowDelete.setOnClickListener { onDelete(c.host, c.username) }
                // Tapping anywhere else on the row opens the edit dialog.
                // The delete button has its own listener, so tapping it
                // does not also trigger edit (the touch is consumed).
                b.root.setOnClickListener { onEdit(c) }
            }
        }
    }
}
