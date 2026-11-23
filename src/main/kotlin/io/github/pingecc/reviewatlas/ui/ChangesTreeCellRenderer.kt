package io.github.pingecc.reviewatlas.ui

import io.github.pingecc.reviewatlas.model.MethodChangeKind
import io.github.pingecc.reviewatlas.model.ReviewTreeNode
import com.intellij.icons.AllIcons
import com.intellij.openapi.vcs.changes.Change
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import java.awt.Color
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class ChangesTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = (value as? DefaultMutableTreeNode)?.userObject ?: return

        when (node) {
            is ReviewTreeNode.Directory -> {
                icon = AllIcons.Nodes.Folder
                append(node.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                val count = node.fileCount()
                if (count > 0) {
                    append("  $count", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                toolTipText = if (node.name.isEmpty()) null else node.name
            }

            is ReviewTreeNode.File -> {
                val entry = node.entry
                icon = iconFor(entry.type)
                val attributes = if (selected) {
                    SimpleTextAttributes.REGULAR_ATTRIBUTES
                } else {
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, colorFor(entry.type))
                }
                append(node.name, attributes)
                toolTipText = buildString {
                    append(entry.relativePath)
                    append("  ·  ")
                    append(labelFor(entry.type))
                }
            }

            is ReviewTreeNode.Method -> {
                val method = node.method
                icon = AllIcons.Nodes.Method
                val attributes = if (selected) {
                    SimpleTextAttributes.REGULAR_ATTRIBUTES
                } else {
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, colorFor(method.kind))
                }
                append(method.name, attributes)
                append(method.signature.removePrefix(method.name), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                if (method.addedLines > 0 || method.removedLines > 0) {
                    append(
                        "  +${method.addedLines} -${method.removedLines}",
                        SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
                    )
                }
                toolTipText = buildString {
                    append(method.signature)
                    append("  ·  ")
                    append(methodLabel(method.kind))
                }
            }

            else -> {
                icon = null
                append(node.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
    }

    private fun iconFor(type: Change.Type): Icon = when (type) {
        Change.Type.NEW -> AllIcons.Actions.AddFile
        Change.Type.DELETED -> AllIcons.General.Delete
        Change.Type.MOVED -> AllIcons.Actions.Diff
        Change.Type.MODIFICATION -> AllIcons.Actions.Edit
    }

    private fun colorFor(type: Change.Type): Color = when (type) {
        Change.Type.NEW -> JBColor(0x1A7F37, 0x7BD88F)
        Change.Type.DELETED -> JBColor(0xCF222E, 0xF07178)
        Change.Type.MOVED -> JBColor(0x8250DF, 0xC297FF)
        Change.Type.MODIFICATION -> JBColor(0x0550AE, 0x79B8FF)
    }

    private fun colorFor(kind: MethodChangeKind): Color = when (kind) {
        MethodChangeKind.NEW -> JBColor(0x1A7F37, 0x7BD88F)
        MethodChangeKind.DELETED -> JBColor(0xCF222E, 0xF07178)
        MethodChangeKind.MODIFIED -> JBColor(0x0550AE, 0x79B8FF)
    }

    private fun labelFor(type: Change.Type): String = when (type) {
        Change.Type.NEW -> "Added"
        Change.Type.DELETED -> "Deleted"
        Change.Type.MOVED -> "Moved"
        Change.Type.MODIFICATION -> "Modified"
    }

    private fun methodLabel(kind: MethodChangeKind): String = when (kind) {
        MethodChangeKind.NEW -> "Added"
        MethodChangeKind.DELETED -> "Deleted"
        MethodChangeKind.MODIFIED -> "Modified"
    }
}