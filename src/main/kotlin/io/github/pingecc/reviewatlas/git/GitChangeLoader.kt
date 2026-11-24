package io.github.pingecc.reviewatlas.git

import io.github.pingecc.reviewatlas.model.ChangeEntry
import io.github.pingecc.reviewatlas.model.CommitItem
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangeListManagerEx
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.vcsUtil.VcsUtil
import git4idea.changes.GitChangeUtils
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

/**
 * All Git access lives here. Every public method is meant to be called from a
 * background thread ([com.intellij.openapi.progress.Task.Backgroundable]);
 * short VFS reads are additionally protected by [ReadAction].
 */
object GitChangeLoader {

    private val LOG = Logger.getInstance(GitChangeLoader::class.java)

    private const val MAX_COMMITS = 30

    data class CommitLoadResult(
        val repositoryCount: Int,
        val commits: List<CommitItem>,
    )

    fun loadCommits(project: Project, indicator: ProgressIndicator): CommitLoadResult {
        val repositories: List<GitRepository> = ReadAction.compute<List<GitRepository>, RuntimeException> {
            GitRepositoryManager.getInstance(project).repositories
        }
        if (repositories.isEmpty()) {
            return CommitLoadResult(0, emptyList())
        }

        val result = ArrayList<CommitItem>()
        for (repository in repositories) {
            indicator.checkCanceled()
            val commits = GitHistoryUtils.history(project, repository.root, "--max-count=$MAX_COMMITS")
            for (commit in commits) {
                result += CommitItem(
                    id = commit.id.asString(),
                    subject = (commit.subject ?: "").ifBlank { "(no message)" },
                    author = commit.author?.name ?: "unknown",
                    commitTime = commit.commitTime,
                    repositoryRoot = repository.root.path,
                    commit = commit,
                )
            }
        }

        return CommitLoadResult(
            repositoryCount = repositories.size,
            commits = result.sortedByDescending { it.commitTime }.take(MAX_COMMITS),
        )
    }

    fun loadEntries(project: Project, item: CommitItem, indicator: ProgressIndicator): List<ChangeEntry> {
        indicator.checkCanceled()

        val repositories: List<GitRepository> = ReadAction.compute<List<GitRepository>, RuntimeException> {
            GitRepositoryManager.getInstance(project).repositories
        }
        val roots = repositories.map { it.root.path }

        val changes: Collection<Change> = if (item.isWorkingTree) {
            loadWorkingTreeChanges(project, repositories, indicator)
        } else {
            val commit = item.commit ?: return emptyList()
            val cached: Collection<Change> = ReadAction.compute<Collection<Change>, RuntimeException> {
                commit.changes
            }
            if (cached.isNotEmpty()) {
                cached
            } else {
                // Root commit or a commit whose changes were not included in the log output.
                GitChangeUtils.getRevisionChanges(project, commit.root, item.id, false, false, false).changes
            }
        }

        indicator.checkCanceled()
        return changes.mapNotNull { toEntry(it, roots) }
            .sortedBy { it.relativePath.lowercase() }
    }

    /**
     * Working tree entries come from two sources: [ChangeListManager] for tracked
     * files and `git ls-files` for untracked ones.
     *
     * The change list is a cache that is only rebuilt when the VCS re-scans a dirty
     * scope. A commit made outside the IDE (`git commit` in a terminal, a Git GUI,
     * another tool) does not modify any working tree file, so no VFS event marks
     * the files dirty and the cached list keeps returning the files that were just
     * committed. Reading it directly therefore shows stale entries until something
     * else refreshes the change list. Force the re-scan and wait for it first.
     */
    private fun loadWorkingTreeChanges(
        project: Project,
        repositories: List<GitRepository>,
        indicator: ProgressIndicator,
    ): Collection<Change> {
        indicator.checkCanceled()
        refreshChangeList(project)
        indicator.checkCanceled()

        // Git's untracked files are not exposed through ChangeListManager#allChanges,
        // and the repository's untracked cache may still be empty during startup.
        // Query Git directly instead; `git ls-files` always reads the real state.
        val untrackedPaths = loadUntrackedFilePaths(project, repositories)

        return ReadAction.compute<Collection<Change>, RuntimeException> {
            val changeListManager = ChangeListManager.getInstance(project)
            val result = ArrayList<Change>(changeListManager.allChanges)
            val knownPaths = result.mapNotNullTo(HashSet()) {
                (it.afterRevision ?: it.beforeRevision)?.file?.path
            }
            for (filePath in untrackedPaths) {
                if (!filePath.isDirectory && knownPaths.add(filePath.path)) {
                    result += Change(null, CurrentContentRevision(filePath))
                }
            }
            result
        }
    }

    /**
     * Marks every file dirty and blocks until [ChangeListManager] has rebuilt its
     * lists, mirroring the platform's own refresh action.
     *
     * Called only from a background task, without a read action held: [ChangeListManagerEx.waitForUpdate]
     * asserts that, and its sibling `waitUntilRefreshed()` is a test-only API.
     */
    private fun refreshChangeList(project: Project) {
        if (project.isDisposed) return
        try {
            VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
            ChangeListManagerEx.getInstanceEx(project).waitForUpdate()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            // Keep the cached list rather than failing the whole load.
            LOG.warn("Cannot refresh the change list before reading the working tree", e)
        }
    }

    private fun loadUntrackedFilePaths(project: Project, repositories: List<GitRepository>): List<FilePath> {
        val result = ArrayList<FilePath>()
        for (repository in repositories) {
            try {
                val handler = GitLineHandler(project, repository.root, GitCommand.LS_FILES)
                handler.addParameters("--others", "--exclude-standard", "-z")
                handler.setSilent(true)
                val output = Git.getInstance().runCommand(handler).getOutputOrThrow()
                // -z avoids Git's quoting/escaping rules for non-ASCII paths.
                for (relativePath in output.split('\u0000')) {
                    if (relativePath.isBlank()) continue
                    val absolutePath = repository.root.path + "/" + relativePath
                    result += VcsUtil.getFilePath(absolutePath, false)
                }
            } catch (_: VcsException) {
                // Keep tracked changes even if listing untracked files fails.
            }
        }
        return result
    }

    private fun toEntry(change: Change, roots: List<String>): ChangeEntry? {
        val file = (change.afterRevision ?: change.beforeRevision)?.file ?: return null
        if (file.isDirectory) return null

        val type = change.type ?: when {
            change.beforeRevision == null -> Change.Type.NEW
            change.afterRevision == null -> Change.Type.DELETED
            else -> Change.Type.MODIFICATION
        }
        val relativePath = relativize(file.path, roots)
        if (relativePath.isBlank()) return null

        return ChangeEntry(
            change = change,
            relativePath = relativePath,
            type = type,
            fileName = relativePath.substringAfterLast('/'),
        )
    }

    private fun relativize(path: String, roots: List<String>): String {
        val normalized = path.replace('\\', '/')
        val root = roots.asSequence()
            .map { it.replace('\\', '/') }
            .filter { normalized == it || normalized.startsWith("$it/") }
            .maxByOrNull { it.length }
        return (root?.let { normalized.removePrefix(it) } ?: normalized).trimStart('/')
    }
}