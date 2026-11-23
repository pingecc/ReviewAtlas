package io.github.pingecc.reviewatlas.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project

/**
 * Tiny threading helpers. Every PSI/VFS read must go through [read]; every UI
 * mutation must go through [invokeLater] so that it lands on the EDT.
 */
object Edt {

    fun <T> read(action: () -> T): T =
        ReadAction.compute<T, RuntimeException> { action() }

    fun invokeLater(project: Project, action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) action()
        }
    }
}