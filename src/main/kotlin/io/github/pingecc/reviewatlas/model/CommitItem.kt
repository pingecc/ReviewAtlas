package io.github.pingecc.reviewatlas.model

import git4idea.GitCommit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One entry of the commit selector: either a real Git commit, or the special
 * "Working Tree" pseudo-commit that represents all uncommitted changes.
 */
class CommitItem(
    val id: String,
    val subject: String,
    val author: String,
    val commitTime: Long,
    val repositoryRoot: String,
    val commit: GitCommit?,
) {
    val isWorkingTree: Boolean
        get() = commit == null

    val shortId: String
        get() = if (isWorkingTree) "WORKTREE" else id.take(8)

    val displayText: String
        get() = if (isWorkingTree) {
            "Working Tree · uncommitted changes"
        } else {
            "$shortId  $subject  ·  $author"
        }

    fun formattedTime(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(commitTime))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CommitItem) return false
        if (isWorkingTree || other.isWorkingTree) return isWorkingTree == other.isWorkingTree
        return id == other.id && repositoryRoot == other.repositoryRoot
    }

    override fun hashCode(): Int =
        if (isWorkingTree) 0 else 31 * id.hashCode() + repositoryRoot.hashCode()

    override fun toString(): String = displayText
}