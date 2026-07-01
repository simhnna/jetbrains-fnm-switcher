package eu.hannaweb.fnm.services

import com.intellij.ide.impl.isTrusted
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.interpreter.wsl.WslNodeInterpreter
import com.intellij.notification.NotificationAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import eu.hannaweb.fnm.notifications.FnmNotifications
import eu.hannaweb.fnm.settings.FnmSettings
import eu.hannaweb.fnm.ui.FnmStatusBarWidget
import java.io.File

/**
 * Project-level service that orchestrates Node version detection and switching.
 *
 * This is the central coordinator: it reads version files, compares them to the
 * active WebStorm Node.js interpreter, and either switches immediately or
 * installs the missing version first (depending on [FnmSettings.autoInstall]).
 *
 * ⚠️ [NodeJsInterpreterManager] and related types are part of the closed-source
 * WebStorm JavaScript plugin and are not stable public API. They are wrapped
 * defensively; any breakage on an IDE upgrade will surface as a logged warning
 * rather than a crash.
 */
@Suppress("UnstableApiUsage")
@Service(Service.Level.PROJECT)
class FnmProjectService(private val project: Project) {

    companion object {
        private val LOG = logger<FnmProjectService>()

        /** Version files in priority order: .node-version wins over .nvmrc */
        private val VERSION_FILES = listOf(".node-version", ".nvmrc")

        /**
         * Accepted version specifiers: a plain numeric Node version, optionally partial
         * (e.g. "24", "24.18", "24.18.0"). This deliberately rejects anything containing
         * path separators, "..", whitespace, or shell metacharacters.
         *
         * Versions are read from untrusted project files (.node-version / .nvmrc) and then
         * used to build filesystem paths ($FNM_DIR/node-versions/v{version}/…) and as
         * `fnm install` arguments. Without this guard a malicious repo could put e.g.
         * "../../../../some/evil" in .node-version and point the project's Node interpreter
         * at an arbitrary executable. Aliases such as "lts/hydrogen" are not supported by
         * fnm's directory layout here anyway, so restricting to numeric specs costs nothing.
         */
        private val VERSION_PATTERN = Regex("""^\d+(\.\d+){0,2}$""")

        fun getInstance(project: Project): FnmProjectService = project.service()

        /** Returns the normalised version if [raw] is a valid numeric spec, else null. */
        fun sanitizeVersion(raw: String): String? {
            val trimmed = raw.trim().removePrefix("v").trim()
            return if (VERSION_PATTERN.matches(trimmed)) trimmed else null
        }

        /** Returns the first non-blank, non-comment line of a version file's contents. */
        private fun firstVersionSpecLine(text: String): String? =
            text.trim().lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }

        /**
         * Returns true if [actual] satisfies [required].
         * Supports partial version specifiers: "24" matches "24.18.0", "24.18" matches "24.18.0".
         */
        fun versionMatches(required: String, actual: String): Boolean =
            actual == required || actual.startsWith("$required.")
    }

    private val appService: FnmAppService
        get() = ApplicationManager.getApplication().service()

    private val settings: FnmSettings
        get() = FnmSettings.getInstance(project)

    /**
     * Where this project's fnm toolchain runs (local host vs. a WSL distro). Resolved per access
     * from the project base path; resolution is a cheap string parse, so it is not cached here.
     */
    private val target: FnmTarget
        get() = FnmTarget.forProject(project)

    // ── Thin wrappers used by the status bar widget ─────────────────────────────────────────
    // These bind the app-level service to this project's [target] so callers (e.g. the widget)
    // don't need to know about target resolution.

    fun resolveFnmPath(): String? = appService.resolveFnmPath(settings.state.fnmBinaryPath, target)

    /** Auto-detects the fnm binary, ignoring the configured path and any cached result. Used by the settings panel. */
    fun autoDetectFnmPath(): String? = appService.resolveFnmPath("", target, forceRefresh = true)

    fun resolveFnmDir(): String? = appService.resolveFnmDir(settings.state.fnmDir, target)

    fun listInstalled(fnmDir: String): List<String> = appService.listInstalled(fnmDir, target)

    /**
     * Cache to avoid redundant interpreter switches when the version hasn't changed.
     * Cleared on branch change or version file edit so we always re-evaluate.
     */
    @Volatile
    private var lastAppliedVersion: String? = null

    /**
     * Cache for [findAllVersionFiles]. Walking the project tree over the WSL 9P share is
     * extremely slow (18 s measured for a moderate project). The result is stable between
     * version-file edits, so we hold onto it and invalidate only when [FnmFileListener]
     * detects a change to a .node-version / .nvmrc file.
     *
     * The startup [FnmStatusBarWidget.scheduleRefresh] always runs [findAllVersionFiles]
     * in the background, so the cache is warm before the user first opens the popup.
     */
    @Volatile
    private var cachedVersionFiles: Map<String, String>? = null

    /** Called by [eu.hannaweb.fnm.listeners.FnmFileListener] when a version file changes. */
    fun invalidateVersionFilesCache() {
        cachedVersionFiles = null
    }

    // ── Version file detection ──────────────────────────────────────────────────────────────

    /**
     * Returns the Node version required by the project (or a specific [dir]), by reading
     * the first version file found (.node-version, then .nvmrc). Returns null if neither
     * file is present or they are blank.
     *
     * The version string is normalised: leading 'v' is stripped, and only the first line
     * is used (nvm sometimes puts a comment on line 2).
     */
    fun detectRequiredVersion(dir: File? = null): String? {
        val resolvedDir = dir ?: File(project.basePath ?: return null)
        for (fileName in VERSION_FILES) {
            val file = File(resolvedDir, fileName)
            if (file.exists()) {
                val rawLine = firstVersionSpecLine(file.readText()) ?: continue
                val version = sanitizeVersion(rawLine)
                if (version != null) return version
                LOG.warn("FNM: Ignoring unsupported version spec '${rawLine.trim()}' in ${file.path}")
            }
        }
        return null
    }

    /**
     * Returns all version files in the project, as a map of relative-path → version
     * (e.g. `{"." -> "20.9.0", "backend" -> "22.0.0"}`).
     *
     * Uses IntelliJ's [FilenameIndex] rather than a raw filesystem walk, so it is
     * essentially instant: no 9P network round-trips over WSL, no manual exclusion of
     * `node_modules` / `.git` (IntelliJ already excludes those from its index), and
     * no `.gitignore` parsing needed (excluded paths are already absent from the index).
     * The result is cached; [invalidateVersionFilesCache] clears it when version files change.
     */
    fun findAllVersionFiles(): Map<String, String> {
        cachedVersionFiles?.let { return it }

        // Normalize to forward slashes for comparison with VirtualFile.path (which is always '/'-separated).
        val rootPath = (project.basePath ?: return emptyMap())
            .replace('\\', '/').trimEnd('/')

        val result = try {
            ReadAction.compute<LinkedHashMap<String, String>, RuntimeException> {
                val map = LinkedHashMap<String, String>()
                if (project.isDisposed) return@compute map
                val scope = GlobalSearchScope.projectScope(project)
                for (fileName in VERSION_FILES) {
                    FilenameIndex.getVirtualFilesByName(fileName, scope).forEach { file ->
                        val parentPath = file.parent?.path ?: return@forEach
                        val relative = when {
                            parentPath == rootPath -> "."
                            parentPath.startsWith("$rootPath/") -> parentPath.removePrefix("$rootPath/")
                            else -> return@forEach  // outside project root (e.g. an external content root)
                        }
                        // Priority: .node-version wins over .nvmrc in the same directory.
                        if (map.containsKey(relative)) return@forEach
                        val rawLine = try {
                            firstVersionSpecLine(String(file.contentsToByteArray()))
                        } catch (e: Exception) {
                            LOG.warn("FNM: Could not read ${file.path}: ${e.message}")
                            null
                        } ?: return@forEach
                        val version = sanitizeVersion(rawLine) ?: return@forEach
                        map[relative] = version
                    }
                }
                map
            }
        } catch (e: IndexNotReadyException) {
            // Index not yet built (startup indexing in progress). Return an uncached empty
            // map so the caller can show a partial result now; FnmStatusBarWidget schedules
            // a follow-up refresh via DumbService.runWhenSmart() which will populate the cache.
            LOG.debug("FNM: FilenameIndex not ready yet, version file lookup deferred")
            return emptyMap()
        }

        cachedVersionFiles = result
        return result
    }

    // ── Interpreter introspection ───────────────────────────────────────────────────────────

    /**
     * Returns the version string of the currently configured Node.js interpreter, derived
     * from the path (e.g. `.../node-versions/v20.9.0/installation/...` → `"20.9.0"`).
     *
     * Returns null if no interpreter is configured, the interpreter is not a local fnm
     * installation, or the Node.js API is unavailable.
     */
    fun getCurrentInterpreterVersion(): String? {
        return try {
            // The configured interpreter may be local (host) or WSL-backed, regardless of where
            // this project lives; read the path from whichever type is in use.
            val path = when (val interpreter = NodeJsInterpreterManager.getInstance(project).interpreter) {
                is NodeJsLocalInterpreter -> interpreter.interpreterSystemDependentPath
                is WslNodeInterpreter -> interpreter.wslInterpreterPath
                else -> return null
            }
            Regex("""node-versions[/\\]v?(\d+\.\d+\.\d+)[/\\]""").find(path)?.groupValues?.get(1)
        } catch (e: Exception) {
            LOG.warn("FNM: Could not read current Node.js interpreter: ${e.message}")
            null
        }
    }

    // ── Main orchestration ──────────────────────────────────────────────────────────────────

    /**
     * Checks whether the project's required Node version matches the active interpreter
     * and switches / installs as needed. This is the single entry point called by all
     * triggers (startup, branch change, file save).
     *
     * May run an fnm/WSL subprocess, which is forbidden on the EDT. Most callers already
     * invoke this from a background thread, but some platform listeners (e.g. VFS events
     * during "Save All") fire synchronously on the EDT — guard here rather than relying on
     * every caller to remember to hop threads.
     */
    fun triggerVersionCheck() {
        if (ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().executeOnPooledThread {
                if (!project.isDisposed) triggerVersionCheck()
            }
            return
        }

        val target = this.target
        val fnmPath = appService.resolveFnmPath(settings.state.fnmBinaryPath, target) ?: return
        val fnmDir = appService.resolveFnmDir(settings.state.fnmDir, target) ?: return

        val required = detectRequiredVersion() ?: return   // no version file → nothing to do
        val current = getCurrentInterpreterVersion()

        // Skip if we already applied this version and the interpreter still matches
        if (current != null && versionMatches(required, current)) return
        if (required == lastAppliedVersion && current != null) return

        if (appService.isInstalled(required, fnmDir, target)) {
            doSwitch(required, fnmDir, target)
        } else {
            // Auto-install runs `fnm install` (a subprocess that downloads & executes) on
            // project open with no user action. Only do that silently in trusted projects;
            // otherwise require an explicit click via the install prompt.
            if (settings.state.autoInstall && project.isTrusted()) {
                installThenSwitch(required, fnmPath, fnmDir, target)
            } else {
                promptInstall(required, fnmPath, fnmDir, target)
            }
        }
    }

    /**
     * Directly switches the interpreter to [version] without checking whether the
     * version is required by a version file. Called from the status bar widget.
     */
    fun switchToVersion(version: String, fnmDir: String) {
        doSwitch(version, fnmDir, target)
    }

    // ── Internal helpers ────────────────────────────────────────────────────────────────────

    private fun doSwitch(version: String, fnmDir: String, target: FnmTarget) {
        val nodePath = appService.getNodePath(version, fnmDir, target) ?: run {
            LOG.warn("FNM: Node path for v$version not found in $fnmDir")
            FnmNotifications.notifyError(
                project,
                "FNM: Node Not Found",
                "Could not locate Node $version under FNM_DIR ($fnmDir). Try running `fnm install $version` manually."
            )
            return
        }

        // NodeJsInterpreterManager.setInterpreter must run on the Event Dispatch Thread
        ApplicationManager.getApplication().invokeLater {
            try {
                val ref = target.createInterpreterRef(nodePath)
                NodeJsInterpreterManager.getInstance(project).setInterpreterRef(ref)
                lastAppliedVersion = version
                LOG.info("FNM: Switched project interpreter to Node $version")

                // Refresh the status bar widget (recomputes its cached text off the EDT)
                FnmStatusBarWidget.refresh(project)

                if (settings.state.notifyOnSwitch) {
                    FnmNotifications.notifyInfo(project, "FNM", "Switched to Node $version")
                }
            } catch (e: Exception) {
                LOG.warn("FNM: Failed to set Node.js interpreter to v$version: ${e.message}")
                FnmNotifications.notifyError(
                    project,
                    "FNM: Switch Failed",
                    "Could not update the Node.js interpreter to v$version. " +
                        "The WebStorm JavaScript plugin API may have changed — check for plugin updates."
                )
            }
        }
    }

    private fun installThenSwitch(version: String, fnmPath: String, fnmDir: String, target: FnmTarget) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "FNM: Installing Node $version", false) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Installing Node $version via fnm…"
                    indicator.isIndeterminate = true
                    if (appService.install(fnmPath, version, target, target.workingDirectory)) {
                        doSwitch(version, fnmDir, target)
                    } else {
                        FnmNotifications.notifyError(
                            project,
                            "FNM: Install Failed",
                            "Failed to install Node $version. " +
                                "Run `fnm install $version` in a terminal to see the full error."
                        )
                    }
                }
            }
        )
    }

    private fun promptInstall(version: String, fnmPath: String, fnmDir: String, target: FnmTarget) {
        val notification = FnmNotifications.buildWarning(
            "FNM: Node Version Required",
            "This project requires Node $version, which is not installed."
        )
        notification.addAction(
            NotificationAction.createSimpleExpiring("Install & Switch") {
                notification.expire()
                installThenSwitch(version, fnmPath, fnmDir, target)
            }
        )
        notification.addAction(
            NotificationAction.createSimpleExpiring("Ignore") {
                notification.expire()
            }
        )
        notification.notify(project)
    }
}
