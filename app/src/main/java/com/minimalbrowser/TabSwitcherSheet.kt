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
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView

/**
 * Bottom-anchored dialog that lists every open tab as a card in a 2-column
 * grid. Tapping a card switches to that tab; tapping the X closes it. The
 * dialog's window stops above the MINIMAL BROWSER footer strip, so the
 * footer remains visible while the sheet is open.
 *
 * Implemented as a plain Dialog (not Material BottomSheetDialog) because we
 * need to reserve a fixed strip at the bottom of the screen for the footer.
 * The root view of the sheet has paddingBottom equal to the footer height,
 * which makes the sheet's own glass card end above that strip. The window
 * background is transparent, so the underlying footer shows through.
 */
class TabSwitcherSheet(
    private val context: Context,
    private val tabManager: TabManager
) {

    private var dialog: Dialog? = null

    fun show() {
        val root = LayoutInflater.from(context)
            .inflate(R.layout.sheet_tabs, null, false)

        val grid = root.findViewById<GridLayout>(R.id.tabGrid)
        val closeSheet = root.findViewById<ImageButton>(R.id.closeSheet)
        val newTab = root.findViewById<View>(R.id.newTabButton)

        rebuildGrid(grid)

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

        d.show()
        dialog = d
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    // ---------------------------------------------------------------------

    private fun rebuildGrid(grid: GridLayout) {
        grid.removeAllViews()
        val inflater = LayoutInflater.from(context)
        val activeIdx = tabManager.getActiveIndex()

        tabManager.tabs.forEachIndexed { index, tab ->
            val cell = inflater.inflate(R.layout.item_tab, grid, false)

            val number = cell.findViewById<TextView>(R.id.tabNumber)
            val title = cell.findViewById<TextView>(R.id.tabTitle)
            val url = cell.findViewById<TextView>(R.id.tabUrl)
            val favicon = cell.findViewById<ImageView>(R.id.tabFavicon)
            val close = cell.findViewById<ImageButton>(R.id.closeTab)

            number.text = (index + 1).toString()

            title.text = tab.title.ifBlank {
                hostLabel(tab.url).ifBlank { context.getString(R.string.tab_untitled) }
            }
            url.text = tab.url

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
                tabManager.closeTab(index)
                if (tabManager.count() <= 1) {
                    dismiss()
                } else {
                    rebuildGrid(grid)
                }
            }

            grid.addView(cell)
        }
    }

    private fun hostLabel(url: String): String {
        if (url.startsWith("minimal://")) return ""
        return runCatching { java.net.URI(url).host }
            .getOrNull()
            ?.removePrefix("www.")
            .orEmpty()
    }
}
