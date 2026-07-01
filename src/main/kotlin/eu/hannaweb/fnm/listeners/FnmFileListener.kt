package eu.hannaweb.fnm.listeners

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import eu.hannaweb.fnm.services.FnmProjectService

/**
 * Watches for VFS events on .node-version and .nvmrc files. When either file is
 * created, modified, or deleted within the project, a version check is triggered.
 *
 * Project-scoped: declared in <projectListeners> so the platform injects [project].
 */
class FnmFileListener(private val project: Project) : BulkFileListener {

    private val versionFileNames = setOf(".node-version", ".nvmrc")

    override fun after(events: List<VFileEvent>) {
        val affected = events.any { event ->
            val name = event.file?.name ?: return@any false
            name in versionFileNames
        }
        // triggerVersionCheck() hops off the EDT itself if needed (this listener can fire
        // synchronously on the EDT, e.g. during "Save All").
        if (affected) {
            val service = FnmProjectService.getInstance(project)
            service.invalidateVersionFilesCache()
            service.triggerVersionCheck()
        }
    }
}
