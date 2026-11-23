package io.github.pingecc.reviewatlas.semantic

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.diff.fragments.LineFragment
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import io.github.pingecc.reviewatlas.model.ChangeEntry
import io.github.pingecc.reviewatlas.model.MethodChange
import io.github.pingecc.reviewatlas.model.MethodChangeKind

/**
 * Extracts the changed Java methods from a [ChangeEntry].
 *
 * A method is considered modified only when its own PSI text differs between
 * the before and after revisions. Import statements, fields, class constants,
 * annotations on other members, and pure line shifts do not affect method text,
 * so they can never mark an unchanged method.
 */
object SemanticMethodExtractor {

    private const val MAX_FILE_SIZE = 2 * 1024 * 1024

    fun enrich(project: Project, entries: List<ChangeEntry>, indicator: ProgressIndicator) {
        if (project.isDisposed) return

        for (entry in entries) {
            indicator.checkCanceled()
            if (!entry.fileName.endsWith(".java", ignoreCase = true)) continue
            if (entry.change.beforeRevision == null && entry.change.afterRevision == null) continue

            try {
                val beforeText = entry.change.beforeRevision?.content ?: ""
                val afterText = entry.change.afterRevision?.content ?: ""
                if (beforeText.length > MAX_FILE_SIZE || afterText.length > MAX_FILE_SIZE) continue

                indicator.text = "Analyzing methods: ${entry.relativePath}"
                entry.methods = extract(project, beforeText, afterText, indicator)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (_: Exception) {
                entry.methods = emptyList()
            }
        }
    }

    private fun extract(
        project: Project,
        beforeText: String,
        afterText: String,
        indicator: ProgressIndicator,
    ): List<MethodChange> {
        if (beforeText.isBlank() && afterText.isBlank()) return emptyList()

        val fragments = try {
            ComparisonManager.getInstance().compareLines(
                beforeText,
                afterText,
                ComparisonPolicy.DEFAULT,
                indicator,
            )
        } catch (_: DiffTooBigException) {
            return emptyList()
        }

        val beforeMethods = parseMethods(project, beforeText)
        val afterMethods = parseMethods(project, afterText)
        if (beforeMethods.isEmpty() && afterMethods.isEmpty()) return emptyList()

        val beforeByKey = beforeMethods.associateBy { it.matchKey }
        val afterByKey = afterMethods.associateBy { it.matchKey }
        val changes = mutableListOf<MethodChange>()

        for (after in afterMethods) {
            val before = beforeByKey[after.matchKey]
            if (before == null) {
                changes += MethodChange(
                    name = after.name,
                    signature = after.signature,
                    reference = after.reference,
                    kind = MethodChangeKind.NEW,
                    line = after.startLine,
                    beforeLine = null,
                    afterLine = after.startLine,
                    parameterCount = after.parameterCount,
                    matchKey = after.matchKey,
                    addedLines = after.lineCount,
                    removedLines = 0,
                )
                continue
            }

            if (after.text == before.text) continue

            changes += MethodChange(
                name = after.name,
                signature = after.signature,
                reference = after.reference,
                kind = MethodChangeKind.MODIFIED,
                line = after.startLine,
                beforeLine = before.startLine,
                afterLine = after.startLine,
                parameterCount = after.parameterCount,
                matchKey = after.matchKey,
                addedLines = addedLines(after, fragments),
                removedLines = removedLines(before, fragments),
            )
        }

        for (before in beforeMethods) {
            if (afterByKey.containsKey(before.matchKey)) continue
            changes += MethodChange(
                name = before.name,
                signature = before.signature,
                reference = before.reference,
                kind = MethodChangeKind.DELETED,
                line = before.startLine,
                beforeLine = before.startLine,
                afterLine = null,
                parameterCount = before.parameterCount,
                matchKey = before.matchKey,
                addedLines = 0,
                removedLines = before.lineCount,
            )
        }

        return changes.sortedBy { it.line }
    }

    private fun parseMethods(project: Project, text: String): List<RawMethod> {
        if (text.isBlank()) return emptyList()

        return ReadAction.compute<List<RawMethod>, RuntimeException> {
            val psiFile = PsiFileFactory.getInstance(project)
                .createFileFromText("ReviewFile.java", JavaFileType.INSTANCE, text) as? PsiJavaFile
                ?: return@compute emptyList()

            PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java)
                .map { method -> toRawMethod(method, text, psiFile.packageName) }
                .sortedBy { it.startLine }
        }
    }

    private fun toRawMethod(method: PsiMethod, text: String, packageName: String): RawMethod {
        val startOffset = method.textRange.startOffset.coerceIn(0, text.length)
        val endOffset = method.textRange.endOffset.coerceIn(startOffset, text.length)
        val parameterTypes = method.parameterList.parameters.map {
            it.typeElement?.text ?: it.type.presentableText
        }
        val containingClass = method.containingClass
        val className = containingClass?.qualifiedName
            ?: listOfNotNull(packageName.takeIf { it.isNotBlank() }, containingClass?.name)
                .joinToString(".")
                .ifBlank { "Unknown" }

        return RawMethod(
            name = method.name,
            signature = method.name + "(" + parameterTypes.joinToString(", ") + ")",
            reference = "$className#${method.name}",
            matchKey = method.name + "(" + parameterTypes.joinToString(",") { normalize(it) } + ")",
            parameterCount = method.parameterList.parametersCount,
            startLine = StringUtil.offsetToLineNumber(text, startOffset),
            endLine = StringUtil.offsetToLineNumber(text, endOffset),
            text = normalizeLineEndings(method.text),
        )
    }

    private fun addedLines(method: RawMethod, fragments: List<LineFragment>): Int =
        fragments.sumOf { fragment ->
            if (fragment.endLine2 > fragment.startLine2 &&
                intersectsRange(fragment.startLine2, fragment.endLine2, method.startLine, method.endLine)
            ) {
                fragment.endLine2 - fragment.startLine2
            } else {
                0
            }
        }

    private fun removedLines(method: RawMethod, fragments: List<LineFragment>): Int =
        fragments.sumOf { fragment ->
            if (fragment.endLine1 > fragment.startLine1 &&
                intersectsRange(fragment.startLine1, fragment.endLine1, method.startLine, method.endLine)
            ) {
                fragment.endLine1 - fragment.startLine1
            } else {
                0
            }
        }

    private fun intersectsRange(
        rangeStart: Int,
        rangeEndExclusive: Int,
        methodStart: Int,
        methodEndInclusive: Int,
    ): Boolean = rangeStart <= methodEndInclusive && rangeEndExclusive > methodStart

    private fun normalize(typeText: String): String = typeText.replace(Regex("\\s+"), "")

    private fun normalizeLineEndings(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    private data class RawMethod(
        val name: String,
        val signature: String,
        val reference: String,
        val matchKey: String,
        val parameterCount: Int,
        val startLine: Int,
        val endLine: Int,
        val text: String,
    ) {
        val lineCount: Int get() = endLine - startLine + 1
    }
}