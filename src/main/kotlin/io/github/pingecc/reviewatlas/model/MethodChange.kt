package io.github.pingecc.reviewatlas.model

enum class MethodChangeKind {
    NEW,
    MODIFIED,
    DELETED,
}

/**
 * A Java method touched by a changed file.
 *
 * [line] is 0-based and refers to the after revision for NEW/MODIFIED, and to
 * the before revision for DELETED.
 */
data class MethodChange(
    val name: String,
    val signature: String,
    val reference: String,
    val kind: MethodChangeKind,
    val line: Int,
    val beforeLine: Int?,
    val afterLine: Int?,
    val parameterCount: Int,
    val matchKey: String,
    val addedLines: Int,
    val removedLines: Int,
)