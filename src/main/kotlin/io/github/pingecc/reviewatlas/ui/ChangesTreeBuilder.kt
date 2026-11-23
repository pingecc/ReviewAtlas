package io.github.pingecc.reviewatlas.ui

import io.github.pingecc.reviewatlas.model.ChangeEntry
import io.github.pingecc.reviewatlas.model.ReviewTreeNode
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Groups changed files by their real path segments:
 *
 *   src/main/kotlin/com/acme/Service.kt
 *   -> src / main / kotlin / com / acme / Service.kt
 *
 * Directories are always listed before files and both groups are sorted
 * case-insensitively, so the tree is stable and predictable.
 */
object ChangesTreeBuilder {

    fun build(entries: List<ChangeEntry>): DefaultMutableTreeNode {
        val root = ReviewTreeNode.Directory("")
        for (entry in entries) {
            val parts = entry.relativePath.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue

            var directory = root
            for (index in 0 until parts.lastIndex) {
                directory = directory.directory(parts[index])
            }
            directory.files += entry
        }
        return toTreeNode(root)
    }

    private fun toTreeNode(directory: ReviewTreeNode.Directory): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(directory)
        directory.directories.values
            .sortedBy { it.name.lowercase() }
            .forEach { node.add(toTreeNode(it)) }
        directory.files
            .sortedBy { it.fileName.lowercase() }
            .forEach { node.add(DefaultMutableTreeNode(ReviewTreeNode.File(it.fileName, it))) }
        return node
    }
}