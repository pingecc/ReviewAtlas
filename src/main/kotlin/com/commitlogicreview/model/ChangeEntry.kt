package com.commitlogicreview.model

import com.intellij.openapi.vcs.changes.Change

/**
 * A changed file, already normalized for the review tree.
 * [relativePath] is relative to the repository root whenever possible.
 */
data class ChangeEntry(
    val change: Change,
    val relativePath: String,
    val type: Change.Type,
    val fileName: String,
)