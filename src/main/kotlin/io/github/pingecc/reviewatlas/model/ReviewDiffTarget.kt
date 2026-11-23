package io.github.pingecc.reviewatlas.model

import com.intellij.diff.util.Side
import com.intellij.openapi.util.Key

/**
 * Attached to a DiffRequest opened from a method node. A DiffExtension uses it
 * to scroll the freshly created diff viewer to the requested method line.
 */
data class ReviewDiffTarget(
    val side: Side,
    val line: Int,
) {
    companion object {
        val KEY: Key<ReviewDiffTarget> = Key.create("ReviewAtlas.DiffTarget")
    }
}