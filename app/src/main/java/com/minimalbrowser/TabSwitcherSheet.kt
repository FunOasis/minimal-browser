package com.minimalbrowser

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class TabSwitcherSheet(
    private val context: Context,
    private val tabManager: TabManager,
    private val onVisibilityChanged: (Boolean) -> Unit = {},
    private val onLastTabCloseRequested: () -> Unit = {}
) {

    private var dialog: Dialog? = null

    fun show() {
        val root = LayoutInflater.from(context)
            .inflate(R.layout.sheet_tabs, null, false)

        val rows = root.findViewById<LinearLayout>(R.id.tabRows)
        val closeSheet = root.findViewById<ImageButton>(R.id.closeSheet)
        val newTab = root.findViewById<View>(R.id.newTabButton)

        rebuildRows(rows)

        val d = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
        }

        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.BOTTOM)
        }

        closeSheet.setOnClickListener { d.dismiss() }
        newTab.setOnClickListener {
            tabManager.create(makeActive = true)
            d.dismiss()
        }

        d.setOnDismissListener { onVisibilityChanged(false) }

        d.show()
        onVisibilityChanged(true)
        dialog = d
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    private fun rebuildRows(container: LinearLayout) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        val activeIdx = tabManager.getActiveIndex()
        val tabs = tabManager.tabs

        var i = 0
        while (i < tabs.size) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            for (j in 0 until 2) {
                if (i + j < tabs.size) {
                    val cell = inflater.inflate(R.layout.item_tab, row, false)
                    bindCell(cell, i + j, activeIdx)
                    row.addView(cell)
                } else {
                    val spacer = View(context)
                    spacer.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    row.addView(spacer)
                }
            }

            container.addView(row)
            i += 2
        }
    }

    private fun bindCell(cell: View, index: Int, activeIdx: Int) {
        val tab = tabManager.tabs[index]

        val number = cell.findViewById<TextView>(R.id.tabNumber)
        val title = cell.findViewById<TextView>(R.id.tabTitle)
        val url = cell.findViewById<TextView>(R.id.tabUrl)
        val favicon = cell.findViewById<ImageView>(R.id.tabFavicon)
        val close = cell.findViewById<ImageButton>(R.id.closeTab)

        number.text = (index + 1).toString()

        title.text = tab.title.ifBlank {
            hostLabel(tab.url).ifBlank { context.getString(R.string.tab_untitled) }
        }
        url.text = displayUrl(tab.url)

        if (tab.favicon != null) {
            favicon.setImageBitmap(tab.favicon)
            favicon.visibility = View.VISIBLE
        } else {
            favicon.setImageDrawable(null)
            favicon.visibility = View.GONE
        }

        cell.background = context.getDrawable(
            if (index == activeIdx) R.drawable.bg_tab_card_active
            else R.drawable.bg_tab_card
        )

        cell.setOnClickListener {
            tabManager.switchTo(index)
            dismiss()
        }

        close.setOnClickListener {
            // Bug 2 fix: closing the last remaining tab is not a silent
            // no-op. Dismiss the sheet and hand control back to the
            // activity, which will raise the exit-confirm dialog.
            if (tabManager.count() <= 1) {
                dismiss()
                onLastTabCloseRequested()
                return@setOnClickListener
            }

            tabManager.closeTab(index)

            if (tabManager.count() <= 1) {
                dismiss()
            } else {
                val parent = cell.parent as? LinearLayout
                val grandparent = parent?.parent as? LinearLayout
                if (grandparent != null) rebuildRows(grandparent)
            }
        }
    }

    private fun hostLabel(url: String): String {
        if (url.startsWith("minimal://")) return ""
        return runCatching { java.net.URI(url).host }
            .getOrNull()
            ?.removePrefix("www.")
            .orEmpty()
    }

    private fun displayUrl(url: String): String {
        if (url.startsWith("minimal://")) return context.getString(R.string.tab_untitled)
        return url
    }
}
