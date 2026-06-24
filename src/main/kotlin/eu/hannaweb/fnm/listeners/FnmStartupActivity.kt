package eu.hannaweb.fnm.listeners

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import eu.hannaweb.fnm.services.FnmProjectService
import eu.hannaweb.fnm.settings.FnmSettings

/**
 * Runs once after a project has finished loading. Triggers an initial Node version
 * check so that the interpreter is correct before the user touches any code.
 *
 * Registered via <postStartupActivity> in plugin.xml.
 */
class FnmStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!FnmSettings.getInstance(project).state.autoSwitchOnProjectOpen) return
        FnmProjectService.getInstance(project).triggerVersionCheck()
    }
}
