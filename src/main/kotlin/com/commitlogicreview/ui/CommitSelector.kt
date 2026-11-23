package com.commitlogicreview.ui

import com.commitlogicreview.model.CommitItem
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel

/**
 * Commit dropdown with a refresh button. Kept intentionally small: it only
 * renders [CommitItem]s and forwards selection/refresh events.
 */
class CommitSelector(
    private val onRefresh: () -> Unit,
    private val onSelectionChanged: (CommitItem?) -> Unit,
) : JPanel(BorderLayout()) {

    private val combo = ComboBox<CommitItem>()
    private val refreshButton = JButton(AllIcons.Actions.Refresh)
    private var suppressEvents = false

    init {
        border = JBUI.Borders.empty(6, 8)

        combo.renderer = object : ColoredListCellRenderer<CommitItem>() {
            override fun customizeCellRenderer(
                list: JList<out CommitItem>,
                value: CommitItem?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                if (value.isWorkingTree) {
                    icon = AllIcons.Actions.Edit
                    append(value.subject, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                } else {
                    icon = AllIcons.Vcs.CommitNode
                    append(value.shortId, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  " + value.subject.take(90), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  ·  " + value.author, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
        }
        combo.maximumRowCount = 15
        combo.toolTipText = "Select a commit (or the working tree) to review"
        combo.addActionListener {
            if (!suppressEvents) onSelectionChanged(combo.selectedItem as? CommitItem)
        }

        refreshButton.apply {
            toolTipText = "Refresh the commit list"
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusable = false
            preferredSize = Dimension(24, 24)
            addActionListener { onRefresh() }
        }

        add(JBLabel("Commit").apply { border = JBUI.Borders.emptyRight(6) }, BorderLayout.WEST)
        add(combo, BorderLayout.CENTER)
        add(refreshButton, BorderLayout.EAST)
    }

    fun setItems(items: List<CommitItem>, preferred: CommitItem?) {
        suppressEvents = true
        try {
            combo.removeAllItems()
            items.forEach { combo.addItem(it) }
            val target = items.firstOrNull { it == preferred } ?: items.firstOrNull()
            combo.selectedItem = target
        } finally {
            suppressEvents = false
        }
    }

    fun selectedItem(): CommitItem? = combo.selectedItem as? CommitItem

    fun setLoading(loading: Boolean) {
        combo.isEnabled = !loading
        refreshButton.isEnabled = !loading
    }
}