package eu.hannaweb.fnm.services

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-level service responsible for all direct interactions with the fnm binary:
 * detecting its location, resolving FNM_DIR, listing installed versions, and running installs.
 *
 * All toolchain specifics (path layout, process invocation, host-side file access) are delegated
 * to the [FnmTarget] passed in by callers, so the same logic works whether fnm runs on the local
 * host or inside a WSL distribution. This service is otherwise stateless.
 */
@Service(Service.Level.APP)
class FnmAppService {

    companion object {
        private val LOG = logger<FnmAppService>()

        /** Matches an fnm version-install directory name, e.g. "v20.9.0". */
        private val VERSION_DIR_PATTERN = Regex("""^v(\d+\.\d+\.\d+)$""")

        /** Orders X.Y.Z version strings numerically (ascending) component-by-component. */
        private val SEMVER_COMPARATOR: Comparator<String> = compareBy(
            { it.split(".")[0].toIntOrNull() ?: 0 },
            { it.split(".")[1].toIntOrNull() ?: 0 },
            { it.split(".")[2].toIntOrNull() ?: 0 },
        )
    }

    // ── Binary / directory resolution ──────────────────────────────────────────────────────

    /**
     * Auto-detection results, keyed by [target]. Populated lazily and kept for the life of the
     * application. Auto-detection can be expensive — for [FnmTarget.Wsl] it spawns a login-shell
     * process (`wsl.exe` startup overhead) — and the resolved binary/dir essentially never change
     * within a session, so re-running it on every status bar popup open made the popup feel
     * extremely laggy. Negative results (not found) are deliberately not cached, so detection is
     * retried (cheaply, via the fast paths first) if the user installs fnm mid-session.
     */
    private val autoFnmPathCache = ConcurrentHashMap<FnmTarget, String>()
    private val autoFnmDirCache = ConcurrentHashMap<FnmTarget, String>()

    /**
     * Resolves the fnm binary path for [target].
     *
     * Priority:
     * 1. [explicitPath] (from settings), if executable.
     * 2. Target auto-detection (PATH + fallbacks on the host; a login-shell lookup in WSL),
     *    cached per [target] unless [forceRefresh] is set (used by the "Auto-detect" settings button).
     */
    fun resolveFnmPath(explicitPath: String = "", target: FnmTarget, forceRefresh: Boolean = false): String? {
        if (explicitPath.isNotBlank()) {
            return if (target.isExecutableFile(explicitPath)) explicitPath
            else {
                LOG.warn("FNM: Configured fnm path '$explicitPath' is not executable.")
                null
            }
        }

        if (!forceRefresh) autoFnmPathCache[target]?.let { return it }

        val detected = target.autoLocateFnm()
        if (detected != null) {
            autoFnmPathCache[target] = detected
            return detected
        }
        LOG.warn("FNM: fnm binary not found. Install fnm or set its path in Settings → Tools → FNM Version Switcher.")
        return null
    }

    /**
     * Resolves FNM_DIR — the directory where fnm stores node versions — for [target].
     *
     * Priority:
     * 1. [explicitDir] (from settings), if it exists.
     * 2. Target auto-detection (FNM_DIR env var + platform defaults), cached per [target].
     */
    fun resolveFnmDir(explicitDir: String = "", target: FnmTarget): String? {
        if (explicitDir.isNotBlank()) {
            return if (target.toHostFile(explicitDir).isDirectory) explicitDir
            else {
                LOG.warn("FNM: Configured FNM_DIR '$explicitDir' does not exist.")
                null
            }
        }

        autoFnmDirCache[target]?.let { return it }

        val detected = target.autoLocateFnmDir()
        if (detected != null) {
            autoFnmDirCache[target] = detected
            return detected
        }
        LOG.warn("FNM: FNM_DIR not found. Set it in Settings → Tools → FNM Version Switcher.")
        return null
    }

    // ── Version path resolution ─────────────────────────────────────────────────────────────

    /**
     * Constructs the toolchain-native path to the `node` binary for [version] using fnm's
     * directory layout (`$FNM_DIR/node-versions/v{version}/installation/bin/node`, or the
     * Windows variant on a native-Windows host).
     *
     * Returns null if the binary does not exist (i.e. the version is not installed).
     */
    fun getNodePath(version: String, fnmDir: String, target: FnmTarget): String? {
        val resolved = resolveFullVersion(version, fnmDir, target)
        val normalized = if (resolved.startsWith("v")) resolved else "v$resolved"
        val nodePath = target.nodeBinaryPath(fnmDir, normalized)
        return if (target.toHostFile(nodePath).exists()) nodePath else null
    }

    /** Returns true if [version] is already installed under [fnmDir]. */
    fun isInstalled(version: String, fnmDir: String, target: FnmTarget): Boolean =
        getNodePath(version, fnmDir, target) != null

    /**
     * Resolves a partial version specifier (e.g. "24" or "24.18") to the highest
     * matching fully-qualified installed version (e.g. "24.18.0") by scanning the
     * fnm node-versions directory. Returns the input unchanged if it is already a
     * full X.Y.Z version or if no matching installation is found.
     */
    private fun resolveFullVersion(version: String, fnmDir: String, target: FnmTarget): String {
        if (Regex("""^\d+\.\d+\.\d+$""").matches(version)) return version
        val versionsDir = target.toHostFile(target.nodeVersionsDir(fnmDir))
        if (!versionsDir.isDirectory) return version
        val prefix = "v${version}."
        return versionsDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(prefix) }
            ?.mapNotNull { VERSION_DIR_PATTERN.matchEntire(it.name)?.groupValues?.get(1) }
            ?.maxWithOrNull(SEMVER_COMPARATOR)
            ?: version
    }

    /**
     * Lists installed Node versions by reading the `node-versions` directory under [fnmDir]
     * directly from the file system, rather than running `fnm list`. fnm's own source of truth
     * for installed versions is that directory, so this gets the same answer without spawning a
     * process — significant for [FnmTarget.Wsl], where every process invocation pays `wsl.exe`
     * startup overhead. Returned newest-first.
     */
    fun listInstalled(fnmDir: String, target: FnmTarget): List<String> {
        val versionsDir = target.toHostFile(target.nodeVersionsDir(fnmDir))
        val dirs = versionsDir.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { VERSION_DIR_PATTERN.matchEntire(it.name)?.groupValues?.get(1) }
            .sortedWith(SEMVER_COMPARATOR.reversed())
    }

    // ── fnm commands ────────────────────────────────────────────────────────────────────────

    /**
     * Runs `fnm install <version>` synchronously (blocking, max 5 minutes).
     * Returns true if the installation succeeded.
     */
    fun install(fnmPath: String, version: String, target: FnmTarget, workingDir: String? = null): Boolean {
        LOG.info("FNM: Installing Node $version…")
        val output = runFnm(target, fnmPath, "install", version, workDir = workingDir)
        if (output == null) {
            LOG.warn("FNM: Install of Node $version failed.")
            return false
        }
        LOG.info("FNM: Successfully installed Node $version.")
        return true
    }

    // ── Internal helpers ────────────────────────────────────────────────────────────────────

    /**
     * Executes an fnm sub-command and returns stdout on success, null on failure.
     *
     * The command is built by [target], which injects the login-shell environment (so fnm sees
     * FNM_DIR, PATH, etc. even when the IDE was not launched from a terminal) and, for WSL,
     * routes execution through `wsl.exe`.
     */
    private fun runFnm(target: FnmTarget, fnmPath: String, vararg args: String, workDir: String? = null): String? {
        return try {
            val cmd = target.commandLine(fnmPath, args.toList(), workDir)
            target.runWithContext {
                val handler = CapturingProcessHandler(cmd)
                val result = handler.runProcess(300_000) // 5-minute cap for installs
                if (result.exitCode == 0) {
                    result.stdout
                } else {
                    LOG.warn("FNM: `fnm ${args.joinToString(" ")}` exited ${result.exitCode}: ${result.stderr.trim()}")
                    null
                }
            }
        } catch (e: Exception) {
            LOG.warn("FNM: Error executing `fnm ${args.joinToString(" ")}`: ${e.message}")
            null
        }
    }
}
