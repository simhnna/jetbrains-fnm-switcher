package eu.hannaweb.fnm.services

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WslPath
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.interpreter.wsl.WslNodeInterpreter
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.nio.file.Paths

/**
 * Describes *where* the fnm toolchain runs for a given project, decoupling it from the OS
 * the IDE itself runs on. This is the key to supporting the "WebStorm on Windows + project
 * in WSL" setup: there the IDE is a Windows process ([SystemInfo.isWindows] is true) but
 * fnm, Node, and the project all live inside a Linux distribution.
 *
 * Every path-, process-, and interpreter-related decision that used to branch on
 * [SystemInfo.isWindows] is now expressed in terms of a [FnmTarget], so the same logic
 * works whether the toolchain is the local host or a WSL guest.
 *
 * "Toolchain path" below always means a path in the *toolchain's* world: a normal host path
 * for [Host], a Linux path for [Wsl]. [toHostFile] bridges back to something the Windows JVM
 * can actually read (a UNC `\\wsl.localhost\…` path for WSL).
 */
sealed interface FnmTarget {

    /** Working directory passed to `fnm install`, in toolchain-native form. */
    val workingDirectory: String?

    /** Maps a toolchain-native path to a [File] the host JVM can read/stat. */
    fun toHostFile(toolchainPath: String): File

    /** True if [toolchainPath] points at an existing, runnable fnm binary. */
    fun isExecutableFile(toolchainPath: String): Boolean

    /** Auto-detects the fnm binary, or null if none is found. */
    fun autoLocateFnm(): String?

    /** Auto-detects FNM_DIR (toolchain-native), or null if none is found. */
    fun autoLocateFnmDir(): String?

    /** The `node-versions` directory under [fnmDir], in toolchain-native form. */
    fun nodeVersionsDir(fnmDir: String): String

    /** Absolute path to the `node` binary for [normalizedVersion] (e.g. "v20.9.0"). */
    fun nodeBinaryPath(fnmDir: String, normalizedVersion: String): String

    /** Builds a command line that runs [exe] [args] inside this target. */
    fun commandLine(exe: String, args: List<String>, workDir: String?): GeneralCommandLine

    /**
     * Runs [block] — which must contain the actual process creation/start (e.g.
     * `CapturingProcessHandler(cmd).runProcess(...)`) — with whatever execution context this
     * target requires. WSL command lines patched by [commandLine] defer their real work to a
     * process-creator lambda that fires when the process is actually started, and that lambda
     * needs a ProgressIndicator or Job on the calling thread (see [FnmTarget.Wsl]). Host is a
     * plain passthrough since native processes have no such requirement.
     */
    fun <T> runWithContext(block: () -> T): T

    /** Wraps [nodeToolchainPath] in the interpreter type appropriate for this target. */
    fun createInterpreterRef(nodeToolchainPath: String): NodeJsInterpreterRef

    companion object {
        private val LOG = logger<FnmTarget>()

        /**
         * Resolves the target for [project]. A project whose base path is a WSL UNC path
         * (`\\wsl$\<distro>\…` / `\\wsl.localhost\<distro>\…`) runs in [Wsl]; everything else
         * — macOS, Linux, and projects on a native Windows drive — runs on [Host].
         */
        fun forProject(project: Project): FnmTarget {
            val base = project.basePath
            if (base != null && SystemInfo.isWindows && WslPath.isWslUncPath(base)) {
                val wslPath = WslPath.parseWindowsUncPath(base)
                if (wslPath != null) {
                    return Wsl(wslPath.distribution, wslPath.linuxPath)
                }
                LOG.warn("FNM: '$base' looks like a WSL path but could not be parsed; using host toolchain.")
            }
            return Host(base)
        }
    }

    // ── Host (macOS / Linux / native Windows) ───────────────────────────────────────────────

    /**
     * The toolchain runs on the same machine and OS as the IDE. This preserves the original
     * pre-WSL behaviour exactly, with all OS specifics keyed off [SystemInfo].
     */
    data class Host(override val workingDirectory: String?) : FnmTarget {

        private fun home(): String = System.getProperty("user.home")

        /** Login-shell environment (cached by the platform). Critical when launched from the Dock. */
        private val shellEnv: Map<String, String> get() = EnvironmentUtil.getEnvironmentMap()

        private val fnmBinaryFallbacks: List<String>
            get() = if (SystemInfo.isWindows) listOf(
                "${home()}/AppData/Local/fnm/fnm.exe",
                "${home()}/AppData/Local/Microsoft/WinGet/Links/fnm.exe",
            ) else listOf(
                "/opt/homebrew/bin/fnm",
                "/usr/local/bin/fnm",
                "${home()}/.fnm/bin/fnm",
                "${home()}/.local/bin/fnm",
            )

        private val fnmDirDefaults: List<String>
            get() = if (SystemInfo.isWindows) listOf(
                "${home()}/AppData/Roaming/fnm",
                "${home()}/AppData/Local/fnm",
            ) else listOf(
                "${home()}/Library/Application Support/fnm",
                "${home()}/.fnm",
                "${home()}/.local/share/fnm",
            )

        override fun toHostFile(toolchainPath: String): File = File(toolchainPath)

        override fun isExecutableFile(toolchainPath: String): Boolean = File(toolchainPath).canExecute()

        override fun autoLocateFnm(): String? {
            val pathDirs = shellEnv["PATH"]?.split(File.pathSeparatorChar) ?: emptyList()
            val binaryNames = if (SystemInfo.isWindows) listOf("fnm.exe", "fnm") else listOf("fnm")
            for (dir in pathDirs) {
                for (name in binaryNames) {
                    val candidate = File(dir, name)
                    if (candidate.canExecute()) return candidate.absolutePath
                }
            }
            return fnmBinaryFallbacks.firstOrNull { File(it).canExecute() }
        }

        override fun autoLocateFnmDir(): String? {
            val envFnmDir = shellEnv["FNM_DIR"]
            if (!envFnmDir.isNullOrBlank() && File(envFnmDir).isDirectory) return envFnmDir
            return fnmDirDefaults.firstOrNull { File(it).isDirectory }
        }

        override fun nodeVersionsDir(fnmDir: String): String =
            Paths.get(fnmDir, "node-versions").toString()

        override fun nodeBinaryPath(fnmDir: String, normalizedVersion: String): String =
            if (SystemInfo.isWindows) {
                // fnm places the node binary directly at installation\node.exe on Windows.
                Paths.get(fnmDir, "node-versions", normalizedVersion, "installation", "node.exe").toString()
            } else {
                Paths.get(fnmDir, "node-versions", normalizedVersion, "installation", "bin", "node").toString()
            }

        override fun commandLine(exe: String, args: List<String>, workDir: String?): GeneralCommandLine =
            GeneralCommandLine(listOf(exe) + args).apply {
                if (workDir != null) withWorkDirectory(workDir)
                environment.putAll(shellEnv)
            }

        override fun <T> runWithContext(block: () -> T): T = block()

        override fun createInterpreterRef(nodeToolchainPath: String): NodeJsInterpreterRef =
            NodeJsInterpreterRef.create(NodeJsLocalInterpreter(nodeToolchainPath))
    }

    // ── WSL (IDE on Windows, toolchain in a Linux distro) ───────────────────────────────────

    /**
     * The toolchain runs inside a WSL distribution. fnm is invoked through `wsl.exe`, Linux
     * paths are translated to UNC for host-side file access, and the Node interpreter is the
     * WSL-aware [WslNodeInterpreter] rather than a local one.
     */
    @Suppress("UnstableApiUsage")
    data class Wsl(val distribution: WSLDistribution, val linuxProjectPath: String) : FnmTarget {

        private companion object {
            val LOG = logger<Wsl>()
        }

        override val workingDirectory: String? get() = linuxProjectPath

        /**
         * Several [WSLDistribution] operations (getUserHome, patchCommandLine) run a
         * `runBlockingCancellable` internally, which requires a ProgressIndicator or Job on the
         * calling thread. Callers here include raw message-bus and pooled-executor threads with
         * no such context (e.g. [FnmBranchListener], `executeOnPooledThread`), so we establish
         * one explicitly rather than relying on the caller to provide it.
         */
        private fun <T> withWslProgressContext(block: () -> T): T =
            ProgressManager.getInstance().runProcess(Computable { block() }, EmptyProgressIndicator())

        /** Linux home (e.g. /home/me), resolved once by the platform. Null if the distro is unreachable. */
        private val userHome: String?
            get() = withWslProgressContext { distribution.userHome }?.trimEnd('/')

        private val fnmBinaryFallbacks: List<String>
            get() = userHome?.let {
                listOf("$it/.local/share/fnm/fnm", "$it/.fnm/fnm", "$it/.local/bin/fnm", "/usr/local/bin/fnm")
            } ?: emptyList()

        private val fnmDirDefaults: List<String>
            get() = userHome?.let {
                listOf("$it/.local/share/fnm", "$it/.fnm")
            } ?: emptyList()

        override fun toHostFile(toolchainPath: String): File =
            File(distribution.getWindowsPath(toolchainPath))

        // canExecute() is unreliable over the 9P share, so existence is the best signal we have.
        override fun isExecutableFile(toolchainPath: String): Boolean = toHostFile(toolchainPath).exists()

        override fun autoLocateFnm(): String? {
            fnmBinaryFallbacks.firstOrNull { toHostFile(it).exists() }?.let { return it }
            // Fall back to a login shell so PATH set up by `eval "$(fnm env)"` etc. is honoured.
            return runInLoginShell(listOf("command", "-v", "fnm"))
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
        }

        override fun autoLocateFnmDir(): String? {
            // Check the standard locations on the filesystem first — this is the common case
            // and costs nothing but a couple of `Path.isDirectory` checks over the 9P share.
            // Only fall back to a login shell (a full wsl.exe process spawn) if FNM_DIR was
            // customized to somewhere non-standard.
            fnmDirDefaults.firstOrNull { toHostFile(it).isDirectory }?.let { return it }
            return runInLoginShell(listOf("sh", "-c", "echo \"\$FNM_DIR\""))
                ?.trim()
                ?.takeIf { it.isNotBlank() && toHostFile(it).isDirectory }
        }

        override fun nodeVersionsDir(fnmDir: String): String = "$fnmDir/node-versions"

        override fun nodeBinaryPath(fnmDir: String, normalizedVersion: String): String =
            "$fnmDir/node-versions/$normalizedVersion/installation/bin/node"

        override fun commandLine(exe: String, args: List<String>, workDir: String?): GeneralCommandLine {
            val cmd = GeneralCommandLine(listOf(exe) + args)
            val options = WSLCommandLineOptions()
                .setExecuteCommandInLoginShell(true)
            if (workDir != null) options.remoteWorkingDirectory = workDir
            return distribution.patchCommandLine(cmd, null, options)
        }

        override fun <T> runWithContext(block: () -> T): T = withWslProgressContext(block)

        override fun createInterpreterRef(nodeToolchainPath: String): NodeJsInterpreterRef =
            NodeJsInterpreterRef.create(WslNodeInterpreter(distribution.msId, nodeToolchainPath))

        /** Runs [command] in a WSL login shell and returns stdout on success, null otherwise. */
        private fun runInLoginShell(command: List<String>): String? = try {
            val cmd = distribution.patchCommandLine(
                GeneralCommandLine(command),
                null,
                WSLCommandLineOptions().setExecuteCommandInLoginShell(true),
            )
            withWslProgressContext {
                val result = CapturingProcessHandler(cmd).runProcess(15_000)
                if (result.exitCode == 0) result.stdout else null
            }
        } catch (e: Exception) {
            LOG.warn("FNM: WSL lookup `${command.joinToString(" ")}` failed: ${e.message}")
            null
        }
    }
}
