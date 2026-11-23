package io.github.pingecc.reviewatlas.ui

import io.github.pingecc.reviewatlas.git.GitChangeLoader
import io.github.pingecc.reviewatlas.model.ChangeEntry
import io.github.pingecc.reviewatlas.model.CommitItem
import io.github.pingecc.reviewatlas.model.MethodChange
import io.github.pingecc.reviewatlas.model.ReviewTreeNode
import io.github.pingecc.reviewatlas.semantic.SemanticMethodExtractor
import io.github.pingecc.reviewatlas.util.DiffOpener
import io.github.pingecc.reviewatlas.util.Edt
import io.github.pingecc.reviewatlas.util.MethodNavigator
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Main ToolWindow content: commit selector on top, directory tree in the
 * middle, status line at the bottom.
 *
 * Threading contract:
 *  - Git commands / VFS reads happen only in [Task.Backgroundable.run].
 *  - Every UI mutation happens in Task callbacks or EDT mouse/key handlers.
 */
class ReviewAtlasPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val selector = CommitSelector(
        onRefresh = { reloadCommits(manual = true) },
        onSelectionChanged = { onCommitSelected(it) },
    )

    private val tree = SimpleTree().apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = ChangesTreeCellRenderer()
        model = DefaultTreeModel(DefaultMutableTreeNode())
    }

    private val statusLabel = JBLabel("Select a commit to review.").apply {
        border = JBUI.Borders.empty(4, 8)
        foreground = NORMAL_STATUS_COLOR
    }

    private var commits: List<CommitItem> = emptyList()
    private var entries: List<ChangeEntry> = emptyList()

    private var loadGeneration = 0
    private var changeGeneration = 0
    private var disposed = false

    /**
     * Git repositories are discovered asynchronously after the project opens.
     * If an auto-restored ToolWindow asks for commits too early, retry a few
     * times instead of permanently showing "no repository".
     */
    private var repositoryRetryCount = 0
    private val retryAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        border = JBUI.Borders.empty()
        add(selector, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        installTreeListeners()
        reloadCommits()
    }

    // ---------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------

    private fun reloadCommits(manual: Boolean = false) {
        if (disposed) return
        if (manual) repositoryRetryCount = 0
        retryAlarm.cancelAllRequests()
        val generation = ++loadGeneration

        selector.setLoading(true)
        setStatus("Loading recent commits…")

        val task = object : Task.Backgroundable(project, "Loading Git Commits", true) {
            private var result: GitChangeLoader.CommitLoadResult? = null
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    result = GitChangeLoader.loadCommits(project, indicator)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.message ?: e.javaClass.simpleName
                }
            }

            override fun onSuccess() {
                if (generation != loadGeneration || disposed || project.isDisposed) return

                val error = failure
                if (error != null) {
                    setStatus("Failed to load commits: $error", isError = true)
                    return
                }
                val loaded = result ?: return
                applyCommits(loaded)
            }

            override fun onThrowable(error: Throwable) {
                if (generation == loadGeneration && !disposed) {
                    setStatus(
                        "Failed to load commits: ${error.message ?: error.javaClass.simpleName}",
                        isError = true,
                    )
                }
            }

            override fun onFinished() {
                if (generation == loadGeneration) {
                    selector.setLoading(false)
                }
            }
        }

        ProgressManager.getInstance().run(task)
    }

    private fun applyCommits(result: GitChangeLoader.CommitLoadResult) {
        if (result.repositoryCount == 0) {
            commits = emptyList()
            if (repositoryRetryCount < MAX_REPOSITORY_RETRIES) {
                repositoryRetryCount++
                selector.setLoading(false)
                selector.setItems(emptyList(), null)
                showEntries(emptyList(), null)
                setStatus("Waiting for Git repositories…")
                retryAlarm.addRequest({ if (!disposed) reloadCommits() }, REPOSITORY_RETRY_DELAY_MS)
            } else {
                selector.setItems(emptyList(), null)
                showEntries(emptyList(), null)
                setStatus("No Git repository found in this project.")
            }
            return
        }
        repositoryRetryCount = 0

        val previous = selector.selectedItem()
        commits = listOf(workingTreeItem()) + result.commits
        val initialSelection = previous?.takeIf { commits.contains(it) }
            ?: commits.firstOrNull { !it.isWorkingTree }
            ?: commits.firstOrNull()
        selector.setItems(commits, initialSelection)

        if (commits.isEmpty()) {
            setStatus("No commits to review.")
            showEntries(emptyList(), null)
        } else {
            onCommitSelected(selector.selectedItem())
        }
    }

    private fun onCommitSelected(item: CommitItem?) {
        if (disposed) return
        if (item == null) {
            showEntries(emptyList(), null)
            return
        }

        val generation = ++changeGeneration
        tree.model = DefaultTreeModel(DefaultMutableTreeNode())
        setStatus("Loading changed files…")

        val task = object : Task.Backgroundable(project, "Loading Changed Files", true) {
            private var loaded: List<ChangeEntry> = emptyList()
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    loaded = GitChangeLoader.loadEntries(project, item, indicator)
                    if (loaded.isNotEmpty()) {
                        try {
                            SemanticMethodExtractor.enrich(project, loaded, indicator)
                        } catch (e: ProcessCanceledException) {
                            throw e
                        } catch (_: Exception) {
                            // Semantic analysis is best-effort; keep the file tree usable.
                        }
                    }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.message ?: e.javaClass.simpleName
                }
            }

            override fun onSuccess() {
                if (generation != changeGeneration || disposed || project.isDisposed) return

                val error = failure
                if (error != null) {
                    setStatus("Failed to load changed files: $error", isError = true)
                    return
                }
                showEntries(loaded, item)
            }

            override fun onThrowable(error: Throwable) {
                if (generation == changeGeneration && !disposed) {
                    setStatus(
                        "Failed to load changed files: ${error.message ?: error.javaClass.simpleName}",
                        isError = true,
                    )
                }
            }
        }

        ProgressManager.getInstance().run(task)
    }

    private fun showEntries(entries: List<ChangeEntry>, item: CommitItem?) {
        this.entries = entries
        tree.model = DefaultTreeModel(ChangesTreeBuilder.build(entries))

        if (entries.isEmpty()) {
            setStatus(
                when {
                    item == null -> "Select a commit to review."
                    item.isWorkingTree -> "No uncommitted changes."
                    else -> "No changed files for ${item.shortId}."
                },
            )
            return
        }

        if (entries.size <= 250) {
            expandDirectoriesOnly()
        } else {
            tree.expandRow(0)
        }

        val suffix = if (item != null && !item.isWorkingTree) {
            " · ${item.shortId} ${item.subject.take(60)}"
        } else {
            ""
        }
        val methodCount = entries.sumOf { it.methods.size }
        val methodSuffix = if (methodCount > 0) ", $methodCount changed method(s)" else ""
        setStatus("${entries.size} changed file(s)$methodSuffix$suffix")
    }

    private fun expandDirectoriesOnly() {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return
        expandDirectoryNode(root, TreePath(root))
    }

    private fun expandDirectoryNode(node: DefaultMutableTreeNode, path: TreePath) {
        if (node.userObject !is ReviewTreeNode.Directory) return
        tree.expandPath(path)
        for (index in 0 until node.childCount) {
            val child = node.getChildAt(index) as? DefaultMutableTreeNode ?: continue
            expandDirectoryNode(child, path.pathByAddingChild(child))
        }
    }

    private fun workingTreeItem(): CommitItem =
        CommitItem(
            id = "",
            subject = "Working Tree · uncommitted changes",
            author = "",
            commitTime = Long.MAX_VALUE,
            repositoryRoot = "",
            commit = null,
        )

    // ---------------------------------------------------------------------
    // Tree interaction
    // ---------------------------------------------------------------------

    private fun installTreeListeners() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showPopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showPopup(e)
            }

            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2) {
                    handleDoubleClick(e)
                }
            }
        })

        val actionKey = "reviewAtlas.openDiff"
        tree.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), actionKey)
        tree.actionMap.put(actionKey, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                when (val node = selectedNode()) {
                    is ReviewTreeNode.Method -> openMethodDiff(node.entry, node.method)
                    is ReviewTreeNode.File -> openDiff(node.entry)
                    else -> Unit
                }
            }
        })
    }

    private fun handleDoubleClick(e: MouseEvent) {
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        when (val node = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
            is ReviewTreeNode.Directory -> {
                if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
            }

            is ReviewTreeNode.File -> {
                tree.selectionPath = path
                openDiff(node.entry)
            }

            is ReviewTreeNode.Method -> {
                tree.selectionPath = path
                openMethodDiff(node.entry, node.method)
            }

            else -> Unit
        }
    }

    private fun showPopup(e: MouseEvent) {
        val node = findNode(e) ?: return
        val menu = JPopupMenu()

        when (node) {
            is ReviewTreeNode.File -> {
                val entry = node.entry
                menu.add(JMenuItem("Open Diff").apply {
                    addActionListener { openDiff(entry) }
                })
                menu.add(JMenuItem("Jump to Source").apply {
                    addActionListener { jumpToSource(entry) }
                })
                menu.addSeparator()
                menu.add(JMenuItem("Copy Relative Path").apply {
                    addActionListener {
                        CopyPasteManager.getInstance().setContents(StringSelection(entry.relativePath))
                    }
                })
            }

            is ReviewTreeNode.Method -> {
                menu.add(JMenuItem("Open Diff").apply {
                    addActionListener { openMethodDiff(node.entry, node.method) }
                })
                menu.add(JMenuItem("Jump to Source").apply {
                    addActionListener { openMethod(node.entry, node.method) }
                })
                menu.addSeparator()
                menu.add(JMenuItem("Copy Reference").apply {
                    addActionListener {
                        CopyPasteManager.getInstance().setContents(StringSelection(node.method.reference))
                    }
                })
            }

            else -> return
        }

        menu.show(tree, e.x, e.y)
    }

    private fun findNode(e: MouseEvent): ReviewTreeNode? {
        val path: TreePath = tree.getPathForLocation(e.x, e.y) ?: return null
        tree.selectionPath = path
        return nodeOf(path)
    }

    private fun selectedNode(): ReviewTreeNode? = nodeOf(tree.selectionPath)

    private fun nodeOf(path: TreePath?): ReviewTreeNode? =
        (path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ReviewTreeNode

    private fun openMethod(entry: ChangeEntry, method: MethodChange) {
        MethodNavigator.openMethod(project, entry, method) { message ->
            if (!disposed) setStatus(message)
        }
    }

    private fun openMethodDiff(entry: ChangeEntry, method: MethodChange) {
        DiffOpener.openMethodDiff(project, entry, method) { detail ->
            if (!disposed) {
                val suffix = detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."
                setStatus("Cannot open diff for ${method.name}()$suffix", isError = true)
            }
        }
    }

    private fun openDiff(entry: ChangeEntry) {
        DiffOpener.openDiff(project, entry.change) { detail ->
            if (!disposed) {
                val suffix = detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."
                setStatus("Cannot open diff for ${entry.relativePath}$suffix", isError = true)
            }
        }
    }

    private fun jumpToSource(entry: ChangeEntry) {
        val virtualFile: VirtualFile? = Edt.read {
            val file = entry.change.afterRevision?.file?.virtualFile
                ?: entry.change.beforeRevision?.file?.virtualFile
            if (file != null && file.isValid) file else null
        }
        if (virtualFile == null) {
            setStatus("Source is not available for ${entry.relativePath} (deleted or moved file).", isError = true)
            return
        }
        try {
            OpenFileDescriptor(project, virtualFile).navigate(true)
        } catch (e: Exception) {
            setStatus("Cannot open ${entry.relativePath}: ${e.message}", isError = true)
        }
    }

    // ---------------------------------------------------------------------
    // Misc
    // ---------------------------------------------------------------------

    private fun setStatus(message: String, isError: Boolean = false) {
        statusLabel.text = message
        statusLabel.foreground = if (isError) ERROR_STATUS_COLOR else NORMAL_STATUS_COLOR
        statusLabel.toolTipText = message
    }

    override fun dispose() {
        disposed = true
        commits = emptyList()
        entries = emptyList()
    }

    private companion object {
        const val MAX_REPOSITORY_RETRIES = 5
        const val REPOSITORY_RETRY_DELAY_MS = 1_500

        val NORMAL_STATUS_COLOR = JBColor(0x666666, 0xB0B0B0)
        val ERROR_STATUS_COLOR = JBColor(0xC7222D, 0xFF6B6B)
    }
}