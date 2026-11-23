package io.github.pingecc.reviewatlas.util

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import io.github.pingecc.reviewatlas.model.ChangeEntry
import io.github.pingecc.reviewatlas.model.MethodChange
import io.github.pingecc.reviewatlas.model.MethodChangeKind
import kotlin.math.abs

/** Opens a changed method in the current working-tree source. */
object MethodNavigator {

    fun openMethod(
        project: Project,
        entry: ChangeEntry,
        method: MethodChange,
        onStatus: (String) -> Unit,
    ) {
        if (project.isDisposed) return

        if (method.kind == MethodChangeKind.DELETED) {
            DiffOpener.openDiff(project, entry.change) { detail ->
                val suffix = detail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                onStatus("Deleted method; opened the file diff.$suffix")
            }
            return
        }

        val task = object : Task.Backgroundable(project, "Locating Method", true) {
            private var target: Target? = null

            override fun run(indicator: ProgressIndicator) {
                target = ReadAction.compute<Target?, RuntimeException> {
                    val file = entry.change.afterRevision?.file?.virtualFile ?: return@compute null
                    if (!file.isValid) return@compute null

                    val psiFile = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile
                        ?: return@compute null
                    val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                        ?: return@compute null

                    val candidates = psiFile.classes.flatMap {
                        it.findMethodsByName(method.name, true).asList()
                    }
                    if (candidates.isEmpty()) {
                        val line = method.line.coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
                        return@compute Target(file, line, exact = false)
                    }

                    val exact = candidates.firstOrNull { signatureKey(it) == method.matchKey }
                    val sameArity = candidates.filter {
                        it.parameterList.parametersCount == method.parameterCount
                    }
                    val chosen = exact
                        ?: sameArity.minByOrNull {
                            abs(document.getLineNumber(it.textOffset) - method.line)
                        }
                        ?: candidates.first()

                    Target(
                        file = file,
                        line = document.getLineNumber(chosen.textOffset),
                        exact = exact != null,
                    )
                }
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                val found = target
                if (found == null) {
                    onStatus("Cannot find source for ${method.name}().")
                    return
                }
                try {
                    OpenFileDescriptor(project, found.file, found.line.coerceAtLeast(0), 0).navigate(true)
                } catch (e: Exception) {
                    onStatus("Cannot open ${method.name}(): ${e.message}")
                    return
                }
                if (!found.exact) {
                    onStatus("Method changed in current source; opened the nearest line.")
                }
            }

            override fun onThrowable(error: Throwable) {
                if (!project.isDisposed) {
                    onStatus("Cannot open ${method.name}(): ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
        ProgressManager.getInstance().run(task)
    }

    private fun signatureKey(method: PsiMethod): String =
        method.name + "(" + method.parameterList.parameters.joinToString(",") {
            normalize(it.typeElement?.text ?: it.type.presentableText)
        } + ")"

    private fun normalize(typeText: String): String = typeText.replace(Regex("\\s+"), "")

    private data class Target(
        val file: VirtualFile,
        val line: Int,
        val exact: Boolean,
    )
}