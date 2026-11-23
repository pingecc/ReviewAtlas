package com.commitlogicreview.model

/**
 * In-memory directory tree used to build the Swing tree model.
 * Directory nodes own their children explicitly so that files are grouped
 * strictly by their real path segments, independent of the order of [Change]s.
 */
sealed class ReviewTreeNode {
    abstract val name: String

    class Directory(override val name: String) : ReviewTreeNode() {
        val directories: MutableMap<String, Directory> = LinkedHashMap()
        val files: MutableList<ChangeEntry> = ArrayList()

        fun directory(childName: String): Directory =
            directories.getOrPut(childName) { Directory(childName) }

        fun fileCount(): Int =
            files.size + directories.values.sumOf { it.fileCount() }
    }

    class File(override val name: String, val entry: ChangeEntry) : ReviewTreeNode()
}