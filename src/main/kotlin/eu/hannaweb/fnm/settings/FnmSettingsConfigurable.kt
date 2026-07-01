package eu.hannaweb.fnm.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import eu.hannaweb.fnm.services.FnmProjectService
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

/**
 * Settings panel shown under Settings → Tools → FNM Version Switcher.
 *
 * Changes are written to [FnmSettings] only when the user clicks Apply / OK.
 */
class FnmSettingsConfigurable(private val project: Project) : Configurable {

    private var fnmPathField: TextFieldWithBrowseButton? = null
    private var fnmDirField: TextFieldWithBrowseButton? = null
    private var autoInstallBox: JCheckBox? = null
    private var autoSwitchBranchBox: JCheckBox? = null
    private var autoSwitchOpenBox: JCheckBox? = null
    private var notifyOnSwitchBox: JCheckBox? = null

    override fun getDisplayName(): String = "FNM Version Switcher"

    override fun createComponent(): JComponent {
        val s = FnmSettings.getInstance(project).state

        fnmPathField = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(
                TextBrowseFolderListener(
                    FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                        .withTitle("Select fnm Binary")
                        .withDescription("Choose the full path to the fnm executable"),
                    project
                )
            )
            text = s.fnmBinaryPath
        }

        fnmDirField = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(
                TextBrowseFolderListener(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        .withTitle("Select FNM_DIR")
                        .withDescription("Choose the directory where fnm stores Node.js versions (\$FNM_DIR)"),
                    project
                )
            )
            text = s.fnmDir
        }

        autoInstallBox = JCheckBox("Auto-install missing Node versions (opt-out)").apply {
            isSelected = s.autoInstall
            toolTipText = "When enabled, fnm will automatically install any Node version required by the project without prompting"
        }

        autoSwitchBranchBox = JCheckBox("Auto-switch interpreter on Git branch change").apply {
            isSelected = s.autoSwitchOnBranchChange
        }

        autoSwitchOpenBox = JCheckBox("Auto-switch interpreter on project open").apply {
            isSelected = s.autoSwitchOnProjectOpen
        }

        notifyOnSwitchBox = JCheckBox("Show notification after successful interpreter switch").apply {
            isSelected = s.notifyOnSwitch
        }

        val detectButton = JButton("Auto-detect fnm path").apply {
            addActionListener {
                // Resolve against this project's toolchain (host or WSL), ignoring any path
                // currently typed in the field so detection reflects auto-discovery.
                val detected = FnmProjectService.getInstance(project).autoDetectFnmPath()
                if (detected != null) {
                    fnmPathField?.text = detected
                    JOptionPane.showMessageDialog(
                        fnmPathField,
                        "Found fnm at:\n$detected",
                        "Detection Successful",
                        JOptionPane.INFORMATION_MESSAGE
                    )
                } else {
                    JOptionPane.showMessageDialog(
                        fnmPathField,
                        "Could not find fnm in PATH or standard locations.\n" +
                            "Install fnm (https://github.com/Schniz/fnm) and enter its path manually.",
                        "fnm Not Found",
                        JOptionPane.WARNING_MESSAGE
                    )
                }
            }
        }

        // ── Layout ────────────────────────────────────────────────────────────────────────

        val panel = JPanel(GridBagLayout())
        var row = 0

        fun label(text: String) = JLabel(text)

        fun gbc(x: Int, y: Int, weightx: Double = 0.0, gridwidth: Int = 1) =
            GridBagConstraints().apply {
                this.gridx = x; this.gridy = y
                this.weightx = weightx; this.gridwidth = gridwidth
                this.fill = GridBagConstraints.HORIZONTAL
                this.insets = Insets(3, 8, 3, 8)
            }

        // Row: fnm binary
        panel.add(label("fnm binary path:"), gbc(0, row))
        panel.add(fnmPathField!!, gbc(1, row, weightx = 1.0))
        row++

        // Row: auto-detect button (right-aligned under field)
        panel.add(JPanel(), gbc(0, row))
        panel.add(detectButton, gbc(1, row))
        row++

        // Row: FNM_DIR
        panel.add(label("FNM_DIR:"), gbc(0, row))
        panel.add(fnmDirField!!, gbc(1, row, weightx = 1.0))
        row++

        // Separator
        panel.add(JSeparator(), gbc(0, row, weightx = 1.0, gridwidth = 2))
        row++

        // Checkboxes (full-width)
        for (box in listOf(autoInstallBox!!, autoSwitchBranchBox!!, autoSwitchOpenBox!!, notifyOnSwitchBox!!)) {
            panel.add(box, gbc(0, row, weightx = 1.0, gridwidth = 2))
            row++
        }

        // Filler to push content to the top
        panel.add(JPanel(), GridBagConstraints().apply {
            gridx = 0; gridy = row; gridwidth = 2; weighty = 1.0
            fill = GridBagConstraints.BOTH
        })

        return panel
    }

    override fun isModified(): Boolean {
        val s = FnmSettings.getInstance(project).state
        return fnmPathField?.text != s.fnmBinaryPath ||
            fnmDirField?.text != s.fnmDir ||
            autoInstallBox?.isSelected != s.autoInstall ||
            autoSwitchBranchBox?.isSelected != s.autoSwitchOnBranchChange ||
            autoSwitchOpenBox?.isSelected != s.autoSwitchOnProjectOpen ||
            notifyOnSwitchBox?.isSelected != s.notifyOnSwitch
    }

    override fun apply() {
        val s = FnmSettings.getInstance(project).state
        s.fnmBinaryPath = fnmPathField?.text?.trim() ?: ""
        s.fnmDir = fnmDirField?.text?.trim() ?: ""
        s.autoInstall = autoInstallBox?.isSelected ?: true
        s.autoSwitchOnBranchChange = autoSwitchBranchBox?.isSelected ?: true
        s.autoSwitchOnProjectOpen = autoSwitchOpenBox?.isSelected ?: true
        s.notifyOnSwitch = notifyOnSwitchBox?.isSelected ?: true
    }

    override fun reset() {
        val s = FnmSettings.getInstance(project).state
        fnmPathField?.text = s.fnmBinaryPath
        fnmDirField?.text = s.fnmDir
        autoInstallBox?.isSelected = s.autoInstall
        autoSwitchBranchBox?.isSelected = s.autoSwitchOnBranchChange
        autoSwitchOpenBox?.isSelected = s.autoSwitchOnProjectOpen
        notifyOnSwitchBox?.isSelected = s.notifyOnSwitch
    }
}
