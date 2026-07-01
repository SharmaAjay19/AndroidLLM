package com.example.androidllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure JVM tests for the agent's file-path resolution — the security-critical logic that
 * decides where read_file/write_file/list_files may touch. Runs on the host (no emulator).
 */
class WorkspaceResolveTest {

    private val base: File = File(System.getProperty("java.io.tmpdir"), "androidllm-ws-test").apply {
        mkdirs()
    }.canonicalFile

    // --- Relative paths resolve inside the workspace ---

    @Test
    fun bareFilename_resolvesInsideWorkspace() {
        val f = Workspace.resolvePath(base, "hello.txt", hasAllFilesAccess = false)
        assertEquals(File(base, "hello.txt").canonicalPath, f?.canonicalPath)
    }

    @Test
    fun relativeSubPath_isAllowed() {
        val f = Workspace.resolvePath(base, "logs/out.txt", hasAllFilesAccess = false)
        assertEquals(File(base, "logs/out.txt").canonicalPath, f?.canonicalPath)
    }

    @Test
    fun leadingSlashesAreStrippedForRelative() {
        // Not an absolute OS path pattern we honor, but should still stay in the sandbox.
        val f = Workspace.resolvePath(base, "sub/../keep.txt", hasAllFilesAccess = false)
        assertEquals(File(base, "keep.txt").canonicalPath, f?.canonicalPath)
    }

    // --- Escapes are rejected ---

    @Test
    fun parentEscape_isRejected() {
        assertNull(Workspace.resolvePath(base, "../escape.txt", hasAllFilesAccess = false))
    }

    @Test
    fun deepParentEscape_isRejected() {
        assertNull(Workspace.resolvePath(base, "a/b/../../../escape.txt", hasAllFilesAccess = false))
    }

    @Test
    fun emptyPath_isRejected() {
        assertNull(Workspace.resolvePath(base, "   ", hasAllFilesAccess = false))
    }

    // --- Absolute shared-storage paths ---

    @Test
    fun absoluteSharedPath_honoredWithAccess() {
        val f = Workspace.resolvePath(base, "/sdcard/Download/out.txt", hasAllFilesAccess = true)
        assertEquals("/sdcard/Download/out.txt", f?.path?.replace('\\', '/'))
    }

    @Test
    fun absoluteStoragePath_honoredWithAccess() {
        val f = Workspace.resolvePath(base, "/storage/emulated/0/AndroidLLM/x.txt", hasAllFilesAccess = true)
        assertEquals("/storage/emulated/0/AndroidLLM/x.txt", f?.path?.replace('\\', '/'))
    }

    @Test
    fun absoluteSharedPath_rejectedWithoutAccess() {
        assertNull(Workspace.resolvePath(base, "/sdcard/Download/out.txt", hasAllFilesAccess = false))
    }

    @Test
    fun absoluteNonSharedPath_rejectedEvenWithAccess() {
        // Must not let the agent write into another app's private data.
        assertNull(Workspace.resolvePath(base, "/data/data/com.other/secret", hasAllFilesAccess = true))
    }
}
