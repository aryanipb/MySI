package com.mysi.agent

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class StorageTest {
    private lateinit var root: File
    private lateinit var workspace: File
    private lateinit var picked: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("mysi").toFile().canonicalFile
        workspace = File(root, "files/workspace").apply { mkdirs() }
        picked = File(root, "cache/picked").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun resolve(path: String) = Storage.resolveIn(workspace, picked, path)

    @Test
    fun relativePathsLandInTheWorkspace() {
        assertEquals(File(workspace, "Documents/notes.txt"), resolve("Documents/notes.txt"))
        assertEquals(workspace, resolve("."))
    }

    @Test
    fun absolutePathsInsideWorkspaceOrPickedAreAllowed() {
        val inPicked = File(picked, "123/photo.jpg")
        assertEquals(inPicked, resolve(inPicked.path))
        val inWorkspace = File(workspace, "Photos/a.jpg")
        assertEquals(inWorkspace, resolve(inWorkspace.path))
    }

    @Test
    fun nothingOutsideTheTwoFoldersResolves() {
        assertNull(resolve(""))
        assertNull(resolve("   "))
        assertNull(resolve("/sdcard/Download/secret.pdf"))
        assertNull(resolve("/data/data/com.mysi.agent/shared_prefs/agent.xml"))
        assertNull(resolve("../../shared_prefs/agent.xml"))
        assertNull(resolve(File(workspace, "../models/x.litertlm").path))
        assertNull(resolve(File(picked, "..").path))
    }

    @Test
    fun aSiblingFolderWithTheSamePrefixIsNotInside() {
        val sibling = File(root, "files/workspace-evil/x.txt")
        assertNull(resolve(sibling.path))
    }

    @Test
    fun symlinksOutOfTheWorkspaceAreRejected() {
        val outside = File(root, "outside.txt").apply { writeText("x") }
        val link = File(workspace, "link.txt")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertNull(resolve("link.txt"))
    }
}
