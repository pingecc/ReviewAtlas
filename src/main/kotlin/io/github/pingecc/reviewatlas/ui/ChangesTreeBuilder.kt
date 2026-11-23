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
 *
 * Middle directories that contain no files and exactly one child directory are
 * compacted into a single row, matching IntelliJ's "compact middle directories"
 * behavior. Compaction stops at the first directory that contains files or has
 * more than one child directory.
 *
 * Example:
 *
 *   src/main/java/com/acme/order/Service.java
 *   src/main/java/com/acme/order/model/Order.java
 *   -> src/main/java/com/acme/order
 *        |- Service.java
 *        `- model/Order.java
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
        return toTreeNode(compact(root, isRoot = true))
    }

    /**
     * Returns a copy of [directory] where every chain of "no files + one child
     * directory" has been merged into a single directory name.
     *
     * The invisible root is never merged into its child, otherwise a project
     * with a single root-to-leaf chain would lose the first directory row.
     */
    private fun compact(directory: ReviewTreeNode.Directory, isRoot: Boolean): ReviewTreeNode.Directory {
        var current = directory
        val label = StringBuilder()
        if (!isRoot) {
            label.append(current.name)
        }

        if (!isRoot) {
            while (current.files.isEmpty() && current.directories.size == 1) {
                current = current.directories.values.first()
                if (label.isNotEmpty()) label.append('/')
                label.append(current.name)
            }
        }

        val compacted = ReviewTreeNode.Directory(label.toString())
        compacted.files += current.files
        for (child in current.directories.values) {
            val compactedChild = compact(child, isRoot = false)
            compacted.directories[compactedChild.name] = compactedChild
        }
        return compacted
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