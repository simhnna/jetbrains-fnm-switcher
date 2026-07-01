package eu.hannaweb.fnm.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Per-project settings persisted to .idea/fnm.xml.
 *
 * All fields are intentionally [var] so they can be mutated in place
 * (the standard IntelliJ PersistentStateComponent pattern).
 */
@Service(Service.Level.PROJECT)
@State(
    name = "FnmSettings",
    storages = [Storage("fnm.xml")]
)
class FnmSettings : PersistentStateComponent<FnmSettings.State> {

    data class State(
        /** Full path to the fnm binary. Empty means auto-detect from shell PATH. */
        var fnmBinaryPath: String = "",
        /** FNM_DIR override. Empty means auto-detect from env / platform defaults. */
        var fnmDir: String = "",
        /** Install the required Node version automatically without prompting. */
        var autoInstall: Boolean = true,
        /** Trigger a version check whenever the active Git branch changes. */
        var autoSwitchOnBranchChange: Boolean = true,
        /** Trigger a version check when the project is opened. */
        var autoSwitchOnProjectOpen: Boolean = true,
        /** Show a brief balloon notification after a successful interpreter switch. */
        var notifyOnSwitch: Boolean = true,
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): FnmSettings = project.service()
    }
}
