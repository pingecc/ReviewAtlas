package io.github.pingecc.reviewatlas.util

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.DiffUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.LogicalPosition
import io.github.pingecc.reviewatlas.model.ReviewDiffTarget

/**
 * Applies a Review Atlas method target after the diff viewer has been created.
 *
 * The platform's initial scroll policy can prefer the first changed block over
 * a requested line, so we explicitly scroll the already-created editor. This
 * also works for both side-by-side and unified viewers.
 */
class ReviewDiffScrollExtension : DiffExtension() {

    override fun onViewerCreated(
        viewer: FrameDiffTool.DiffViewer,
        context: DiffContext,
        request: DiffRequest,
    ) {
        val target = request.getUserData(ReviewDiffTarget.KEY) ?: return

        // Let the viewer finish its initial rediff/layout before moving it.
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().invokeLater {
                try {
                    scrollToTarget(viewer, target)
                } catch (_: Exception) {
                    // The diff may have been closed already; nothing to do.
                }
            }
        }
    }

    private fun scrollToTarget(viewer: FrameDiffTool.DiffViewer, target: ReviewDiffTarget) {
        when (viewer) {
            is TwosideTextDiffViewer -> {
                val editor = viewer.getEditor(target.side) ?: return
                val line = target.line.coerceAtLeast(0)
                editor.caretModel.moveToLogicalPosition(LogicalPosition(line, 0))
                DiffUtil.scrollEditor(editor, line, 0, false)
            }

            is UnifiedDiffViewer -> {
                val unifiedLine = viewer.transferLineToOneside(target.side, target.line)
                if (unifiedLine >= 0) {
                    viewer.editor.caretModel.moveToLogicalPosition(LogicalPosition(unifiedLine, 0))
                    DiffUtil.scrollEditor(viewer.editor, unifiedLine, 0, false)
                }
            }
        }
    }
}