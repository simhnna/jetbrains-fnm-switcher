package eu.hannaweb.fnm.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Unit tests for the pure version-handling logic. These intentionally avoid the
 * IntelliJ platform test fixtures and exercise only side-effect-free helpers plus
 * filesystem-based path resolution (via @TempDir).
 */
class FnmVersionLogicTest {

    // ── versionMatches ─────────────────────────────────────────────────────────

    @Test
    fun `versionMatches accepts exact and partial specifiers`() {
        assertTrue(FnmProjectService.versionMatches("20.9.0", "20.9.0"))
        assertTrue(FnmProjectService.versionMatches("20", "20.9.0"))
        assertTrue(FnmProjectService.versionMatches("20.9", "20.9.0"))
    }

    @Test
    fun `versionMatches rejects non-matching or prefix-collision versions`() {
        assertFalse(FnmProjectService.versionMatches("20", "200.0.0")) // not "20."
        assertFalse(FnmProjectService.versionMatches("20.9.0", "20.9.1"))
        assertFalse(FnmProjectService.versionMatches("18", "20.9.0"))
    }

    // ── sanitizeVersion (security guard) ───────────────────────────────────────

    @Test
    fun `sanitizeVersion accepts valid numeric specs and strips leading v`() {
        assertEquals("20", FnmProjectService.sanitizeVersion("20"))
        assertEquals("20.9", FnmProjectService.sanitizeVersion("20.9"))
        assertEquals("20.9.0", FnmProjectService.sanitizeVersion("20.9.0"))
        assertEquals("20.9.0", FnmProjectService.sanitizeVersion("v20.9.0"))
        assertEquals("20.9.0", FnmProjectService.sanitizeVersion("  v20.9.0  "))
    }

    @Test
    fun `sanitizeVersion rejects path traversal and injection attempts`() {
        assertNull(FnmProjectService.sanitizeVersion("../../../../etc/passwd"))
        assertNull(FnmProjectService.sanitizeVersion("20.9.0/../../evil"))
        assertNull(FnmProjectService.sanitizeVersion("\$(rm -rf ~)"))
        assertNull(FnmProjectService.sanitizeVersion("20; rm -rf /"))
        assertNull(FnmProjectService.sanitizeVersion("lts/hydrogen"))
        assertNull(FnmProjectService.sanitizeVersion("latest"))
        assertNull(FnmProjectService.sanitizeVersion(""))
        assertNull(FnmProjectService.sanitizeVersion("20.9.0-beta"))
    }

    // ── listInstalled (filesystem-backed) ───────────────────────────────────────

    @Test
    fun `listInstalled reads version directories and sorts newest-first`(@TempDir tmp: Path) {
        val fnmDir = tmp.toFile()
        for (v in listOf("18.17.1", "22.0.0", "20.9.0")) {
            File(fnmDir, "node-versions/v$v").mkdirs()
        }
        val installed = FnmAppService().listInstalled(fnmDir.absolutePath, FnmTarget.Host(null))
        assertEquals(listOf("22.0.0", "20.9.0", "18.17.1"), installed)
    }

    @Test
    fun `listInstalled ignores non-version entries and non-directories`(@TempDir tmp: Path) {
        val fnmDir = tmp.toFile()
        File(fnmDir, "node-versions/v20.9.0").mkdirs()
        File(fnmDir, "node-versions/lts-latest").mkdirs()
        File(fnmDir, "node-versions").apply { mkdirs() }
        File(fnmDir, "node-versions/v22.0.0").apply { /* a file, not a directory */
            parentFile.mkdirs()
            writeText("")
        }
        val installed = FnmAppService().listInstalled(fnmDir.absolutePath, FnmTarget.Host(null))
        assertEquals(listOf("20.9.0"), installed)
    }

    @Test
    fun `listInstalled returns empty when node-versions does not exist`(@TempDir tmp: Path) {
        assertTrue(FnmAppService().listInstalled(tmp.toFile().absolutePath, FnmTarget.Host(null)).isEmpty())
    }

    // ── getNodePath / resolveFullVersion (filesystem-backed) ───────────────────

    @Test
    fun `getNodePath resolves a full version to the installed node binary`(@TempDir tmp: Path) {
        val fnmDir = tmp.toFile()
        val binDir = File(fnmDir, "node-versions/v20.9.0/installation/bin")
        binDir.mkdirs()
        val node = File(binDir, "node").apply { writeText("#!/bin/sh\n") }

        val resolved = FnmAppService().getNodePath("20.9.0", fnmDir.absolutePath, FnmTarget.Host(null))
        // On non-Windows the binary lives at .../installation/bin/node
        if (!com.intellij.openapi.util.SystemInfo.isWindows) {
            assertEquals(node.absolutePath, resolved?.let { File(it).absolutePath })
        }
    }

    @Test
    fun `getNodePath resolves a partial version to the highest matching install`(@TempDir tmp: Path) {
        if (com.intellij.openapi.util.SystemInfo.isWindows) return
        val fnmDir = tmp.toFile()
        for (v in listOf("20.9.0", "20.18.2", "20.10.0", "22.0.0")) {
            File(fnmDir, "node-versions/v$v/installation/bin").mkdirs()
            File(fnmDir, "node-versions/v$v/installation/bin/node").writeText("")
        }
        val resolved = FnmAppService().getNodePath("20", fnmDir.absolutePath, FnmTarget.Host(null))
        assertTrue(resolved!!.contains("v20.18.2"), "expected highest 20.x, got $resolved")
    }

    @Test
    fun `getNodePath returns null when the version is not installed`(@TempDir tmp: Path) {
        assertNull(FnmAppService().getNodePath("99.0.0", tmp.toFile().absolutePath, FnmTarget.Host(null)))
    }
}
