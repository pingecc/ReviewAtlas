package io.github.pingecc.reviewatlas.util

import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.changes.ui.ChangeDiffRequestChain

/**
 * Opens the native IntelliJ diff viewer for a [Change].
 *
 * Creating the diff producer may resolve VCS revisions, therefore it runs in a
 * background task. Only the final [DiffManager.showDiff] call is made on the EDT.
 */
object DiffOpener {

    fun openDiff(project: Project, change: Change, onError: (String?) -> Unit) {
        if (project.isDisposed) return

        val task = object : Task.Backgroundable(project, "Preparing Diff", true) {
            private var producer: ChangeDiffRequestProducer? = null
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    producer = Edt.read<ChangeDiffRequestProducer?> {
                        if (ChangeDiffRequestProducer.canCreate(project, change)) {
                            ChangeDiffRequestProducer.create(project, change)
                        } else {
                            null
                        }
                    }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.message ?: e.javaClass.simpleName
                }
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                val requestProducer = producer
                if (requestProducer == null) {
                    onError(failure)
                    return
                }
                try {
                    val chain = ChangeDiffRequestChain(listOf(requestProducer), 0)
                    DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
                } catch (e: Exception) {
                    onError(e.message ?: e.javaClass.simpleName)
                }
            }

            override fun onThrowable(error: Throwable) {
                if (!project.isDisposed) {
                    onError(error.message ?: error.javaClass.simpleName)
                }
            }
        }
        ProgressManager.getInstance().run(task)
    }
}