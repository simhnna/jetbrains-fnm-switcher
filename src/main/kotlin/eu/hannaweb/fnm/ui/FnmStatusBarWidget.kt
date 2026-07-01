package eu.hannaweb.fnm.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import eu.hannaweb.fnm.services.FnmProjectService
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JLabel

// ── Factory ──────────────────────────────────────────────────────────────────────────────────

/**
 * Registers the FNM status bar widget with the IDE.
 * Declared in plugin.xml as a <statusBarWidgetFactory>.
 */
class FnmStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = FnmStatusBarWidget.ID
    override fun getDisplayName(): String = "FNM Node Version"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = FnmStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

// ── Widget ───────────────────────────────────────────────────────────────────────────────────

/**
 * A status bar widget that displays the currently active Node.js version and
 * provides a popup for manual switching.
 *
 * Implements [CustomStatusBarWidget] so we own the [JLabel] directly and can call
 * [JLabel.setText] from the EDT — bypassing the platform's [StatusBar.updateWidget]
 * mechanism, which in IntelliJ 2024.2 does not re-query [StatusBarWidget.TextPresentation.getText]
 * for factory-managed widgets due to the coroutine-based rendering pipeline wrapping the
 * component in a plain JPanel (not a StatusBarWidgetWrapper).
 *
 * All potentially slow work (reading version files, walking the project tree,
 * reading the interpreter, scanning installed Node versions) is performed off the EDT.
 */
class FnmStatusBarWidget(private val project: Project) :
    StatusBarWidget, CustomStatusBarWidget {

    companion object {
        const val ID = "FnmStatusBarWidget"

        private val LOG = logger<FnmStatusBarWidget>()

        /**
         * Asynchronously refreshes the widget's cached presentation for [project], if the
         * widget is currently installed. Safe to call from any thread.
         */
        fun refresh(project: Project) {
            val statusBar = WindowManager.getInstance().getStatusBar(project) ?: return
            (statusBar.getWidget(ID) as? FnmStatusBarWidget)?.scheduleRefresh()
        }
    }

    /**
     * The component displayed in the status bar. We own this label and set its text directly
     * from the EDT, which is the reliable update path in IntelliJ 2024.2+.
     */
    private val label = JLabel("Node …").apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = showPopup(e)
        })
    }

    private data class WidgetState(val text: String, val tooltip: String)

    /**
     * Guards against overlapping popup fetches. Without this, clicking again while the first
     * click's background fetch is still running starts a second fetch anchored to wherever the
     * mouse happens to be on that second click.
     */
    private val popupLoading = AtomicBoolean(false)

    // ── StatusBarWidget ───────────────────────────────────────────────────────────────────

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation? = null

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {
        LOG.info("FNM: widget installed for '${project.name}', dumb=${DumbService.isDumb(project)}")
        scheduleRefresh()
        DumbService.getInstance(project).runWhenSmart {
            if (!project.isDisposed) {
                LOG.info("FNM: smart mode reached for '${project.name}', scheduling refresh")
                scheduleRefresh()
            }
        }
    }

    override fun dispose() {
        LOG.info("FNM: widget disposed for '${project.name}'")
    }

    // ── Async refresh ──────────────────────────────────────────────────────────────────────

    /** Recomputes state on a background thread, then sets the label text directly on the EDT. */
    fun scheduleRefresh() {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            var state: WidgetState? = null
            try {
                state = computeState()
                LOG.info("FNM: refreshed '${project.name}': text='${state.text}'")
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("FNM: computeState failed for '${project.name}': ${e.javaClass.simpleName}: ${e.message}", e)
            }
            val resolved = state ?: return@executeOnPooledThread
            app.invokeLater({
                if (!project.isDisposed) {
                    label.text = resolved.text
                    label.toolTipText = resolved.tooltip
                }
            }, ModalityState.any())
        }
    }

    private fun computeState(): WidgetState {
        val service = FnmProjectService.getInstance(project)
        val active = ReadAction.nonBlocking(Callable { safeGetCurrentVersion() }).executeSynchronously()
        val required = service.detectRequiredVersion()
        val versionFiles = service.findAllVersionFiles()

        val text = when {
            active == null -> "Node: ?"
            required != null && !FnmProjectService.versionMatches(required, active) -> "⚠ Node $active"
            else -> "Node $active"
        }

        val tooltip = buildString {
            if (required != null && active != null && !FnmProjectService.versionMatches(required, active)) {
                appendLine("⚠ Version mismatch: active=$active, required=$required")
            }
            if (versionFiles.isEmpty()) {
                append("FNM: No .node-version / .nvmrc found in project")
            } else {
                append("FNM version files: ")
                append(versionFiles.entries.joinToString(", ") { "${it.key}: v${it.value}" })
            }
        }.trim()

        return WidgetState(text, tooltip)
    }

    // ── Popup ────────────────────────────────────────────────────────────────────────────

    private fun showPopup(event: MouseEvent) {
        if (!popupLoading.compareAndSet(false, true)) return
        val where = RelativePoint(event)
        val loadingPopup = createLoadingPopup()
        loadingPopup.show(where)

        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            if (project.isDisposed) {
                popupLoading.set(false)
                app.invokeLater({ loadingPopup.cancel() }, ModalityState.any())
                return@executeOnPooledThread
            }
            val service = FnmProjectService.getInstance(project)
            val timings = LinkedHashMap<String, Long>()
            val fnmPath = timed(timings, "resolveFnmPath") { service.resolveFnmPath() }
            val fnmDir = timed(timings, "resolveFnmDir") { service.resolveFnmDir() }
            val versionFiles = timed(timings, "findAllVersionFiles") { service.findAllVersionFiles() }
            val installed = timed(timings, "listInstalled") {
                if (fnmDir != null) service.listInstalled(fnmDir) else emptyList()
            }
            val currentVersion = timed(timings, "currentVersion") {
                ReadAction.nonBlocking(Callable { safeGetCurrentVersion() }).executeSynchronously()
            }
            LOG.info(
                "FNM: popup data gathered in ${timings.values.sum()}ms total " +
                    "(${timings.entries.joinToString(", ") { "${it.key}=${it.value}ms" }})"
            )

            app.invokeLater({
                popupLoading.set(false)
                loadingPopup.cancel()
                if (project.isDisposed) return@invokeLater
                val actions = buildActions(fnmPath, fnmDir, versionFiles, installed, currentVersion)
                JBPopupFactory.getInstance().createActionGroupPopup(
                    "FNM Node Version",
                    actions,
                    DataContext.EMPTY_CONTEXT,
                    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                    false
                ).show(where)
            }, ModalityState.any())
        }
    }

    private fun createLoadingPopup(): JBPopup {
        val label = JLabel("Loading FNM data…", AnimatedIcon.Default.INSTANCE, JLabel.LEFT)
        label.border = JBUI.Borders.empty(8, 12)
        return JBPopupFactory.getInstance()
            .createComponentPopupBuilder(label, null)
            .setCancelOnClickOutside(true)
            .createPopup()
    }

    private fun buildActions(
        fnmPath: String?,
        fnmDir: String?,
        versionFiles: Map<String, String>,
        installed: List<String>,
        currentVersion: String?,
    ): DefaultActionGroup {
        val actions = DefaultActionGroup()

        if (versionFiles.isNotEmpty()) {
            actions.addSeparator("Project version files")
            versionFiles.forEach { (path, version) ->
                val lbl = if (path == ".") "/ → Node $version" else "$path/ → Node $version"
                actions.add(switchAction(lbl, version, fnmDir))
            }
        }

        if (installed.isNotEmpty()) {
            actions.addSeparator("Installed versions")
            installed.forEach { version ->
                val prefix = if (version == currentVersion) "✓  " else "    "
                actions.add(switchAction("${prefix}Node $version", version, fnmDir))
            }
        }

        actions.addSeparator()
        actions.add(object : AnAction("↻  Auto-detect from version file") {
            override fun actionPerformed(e: AnActionEvent) {
                FnmProjectService.getInstance(project).triggerVersionCheck()
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        })

        if (fnmPath == null) {
            actions.addSeparator()
            actions.add(object : AnAction("⚠ fnm binary not found — open settings") {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "FNM Version Switcher")
                }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
        }

        return actions
    }

    private fun switchAction(label: String, version: String, fnmDir: String?): AnAction =
        object : AnAction(label) {
            override fun actionPerformed(e: AnActionEvent) {
                if (fnmDir == null) return
                FnmProjectService.getInstance(project).switchToVersion(version, fnmDir)
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = fnmDir != null
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────

    private inline fun <T> timed(timings: MutableMap<String, Long>, label: String, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        timings[label] = (System.nanoTime() - start) / 1_000_000
        return result
    }

    private fun safeGetCurrentVersion(): String? =
        try { FnmProjectService.getInstance(project).getCurrentInterpreterVersion() }
        catch (e: Exception) { null }
}
