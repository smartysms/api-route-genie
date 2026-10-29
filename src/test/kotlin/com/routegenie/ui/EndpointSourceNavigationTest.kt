package com.routegenie.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * The window navigates from the UI thread, where the platform does not grant read access implicitly (a real IDE reported
 * "Read access is allowed from inside read-action only" for the Navigate button). Calling from a pooled thread that holds no
 * read action reproduces that situation: the code must take its own.
 */
class EndpointSourceNavigationTest : BasePlatformTestCase() {
    private fun relativePath(): Pair<String, com.intellij.openapi.vfs.VirtualFile> {
        val file = myFixture.addFileToProject("app/routes.py", "x = 1\n").virtualFile
        val relative = VfsUtilCore.getRelativePath(file, project.guessProjectDir()!!)!!
        return relative to file
    }

    private fun <T> offTheCallerLock(block: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread(Callable { block() }).get(30, TimeUnit.SECONDS)

    fun testResolveTakesItsOwnReadAction() {
        val (relative, file) = relativePath()
        assertEquals(file, offTheCallerLock { EndpointSourceNavigation.resolve(project, relative) })
    }

    fun testResolveReturnsNullForAMissingFile() {
        assertNull(offTheCallerLock { EndpointSourceNavigation.resolve(project, "app/does_not_exist.py") })
    }

    fun testOffsetIsClampedIntoTheFile() {
        assertEquals(0, EndpointSourceNavigation.clampOffset(-5, 10))
        assertEquals(10, EndpointSourceNavigation.clampOffset(99, 10))
        assertEquals(4, EndpointSourceNavigation.clampOffset(4, 10))
    }
}
