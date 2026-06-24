package eu.hannaweb.fnm.listeners

import com.intellij.openapi.project.Project
import eu.hannaweb.fnm.services.FnmProjectService
import eu.hannaweb.fnm.settings.FnmSettings
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

/**
 * Listens for Git repository state changes (branch switches, HEAD moves) and
 * triggers a Node version check when the active branch changes.
 *
 * The listener is project-scoped (declared in <projectListeners> in plugin.xml),
 * so [project] is injected automatically by the IntelliJ Platform.
 */
class FnmBranchListener(private val project: Project) : GitRepositoryChangeListener {

    @Volatile
    private var lastBranch: String? = null

    override fun repositoryChanged(repository: GitRepository) {
        if (!FnmSettings.getInstance(project).state.autoSwitchOnBranchChange) return

        // Use the branch name, falling back to the detached-HEAD revision hash
        val currentBranch = repository.currentBranch?.name
            ?: repository.currentRevision
            ?: return

        if (currentBranch == lastBranch) return
        lastBranch = currentBranch

        FnmProjectService.getInstance(project).triggerVersionCheck()
    }
}
