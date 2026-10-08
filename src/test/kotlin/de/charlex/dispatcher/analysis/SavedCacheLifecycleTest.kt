package de.charlex.dispatcher.analysis

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import de.charlex.dispatcher.model.Dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Assume.assumeNoException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class SavedCacheLifecycleTest : BasePlatformTestCase() {
    private lateinit var cacheProject: Path
    private val lifetimes = mutableListOf<Job>()
    private val libraries = mutableListOf<Library>()

    override fun getProjectDescriptor() = LightJavaCodeInsightFixtureTestCase.JAVA_21

    override fun setUp() {
        super.setUp()
        cacheProject = Files.createTempDirectory("dispatcher-saved-cache")
        System.getProperty("dispatcher.fixture.libraries").split(File.pathSeparator).forEach { path ->
            val library = File(path)
            PsiTestUtil.addLibrary(module, library.nameWithoutExtension, library.parent, library.name)
        }
    }

    override fun tearDown() {
        try {
            lifetimes.forEach { it.cancel() }
            await("Cache service disposal") { lifetimes.all { it.isCompleted } }
            libraries.forEach { PsiTestUtil.removeLibrary(module, it) }
            cacheProject.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testReopeningReusesValidatedGraphsAndForceRefreshBypassesHash() {
        val file = source()
        val first = analysis()
        first.startAnalysis()
        awaitCurrent(first, file)
        await("First cache write") { Files.isRegularFile(cachePath()) }
        stopLastService()

        val reopened = analysis()
        reopened.startAnalysis()
        awaitCurrent(reopened, file)
        assertTrue("Unchanged sources must reuse disk graphs", reopened.lastResolvedFiles.isEmpty())
        assertEquals(setOf(Dispatcher.IO), lastCall(reopened, file).summary.dispatchers.known)

        reopened.reanalyzeFile(file.virtualFile)
        awaitCurrent(reopened, file)
        assertTrue(file.virtualFile.url in reopened.lastResolvedFiles)
        assertEquals(setOf(Dispatcher.IO), lastCall(reopened, file).summary.dispatchers.known)
    }

    fun testReopeningRejectsChangedSourceAndCorruptCache() {
        val file = source()
        val first = analysis()
        first.startAnalysis()
        awaitCurrent(first, file)
        await("First cache write") { Files.isRegularFile(cachePath()) }
        stopLastService()

        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(document.text.replace("Dispatchers.IO", "Dispatchers.Default"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        FileDocumentManager.getInstance().saveDocument(document)
        val reopened = analysis()
        reopened.startAnalysis()
        awaitCurrent(reopened, file)
        assertTrue(file.virtualFile.url in reopened.lastResolvedFiles)
        assertEquals(setOf(Dispatcher.Default), lastCall(reopened, file).summary.dispatchers.known)
        stopLastService()

        Files.write(cachePath(), byteArrayOf(1, 2, 3))
        val recovered = analysis()
        recovered.startAnalysis()
        awaitCurrent(recovered, file)
        assertTrue(file.virtualFile.url in recovered.lastResolvedFiles)
        assertEquals(setOf(Dispatcher.Default), lastCall(recovered, file).summary.dispatchers.known)
    }

    fun testDeletingBuildCacheRebuildsWithoutAnEditorRequest() {
        val file = source()
        val current = analysis()
        current.startAnalysis()
        awaitCurrent(current, file)
        await("First cache write") { Files.isRegularFile(cachePath()) }
        val cacheFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(cachePath())!!

        WriteCommandAction.runWriteCommandAction(project) { cacheFile.parent.delete(this) }

        await("Cleaned cache regenerated") { Files.isRegularFile(cachePath()) && current.hasCurrentAnalysis(file) }
        assertTrue(file.virtualFile.url in current.lastResolvedFiles)
        assertEquals(setOf(Dispatcher.IO), lastCall(current, file).summary.dispatchers.known)
    }

    fun testExternalSymlinkTargetChangeInvalidatesAndRebuildsWithoutSourceSave() {
        val classes = Files.createDirectories(cacheProject.resolve("library/META-INF"))
        val external = Files.createDirectories(cacheProject.resolve("external"))
        val target = Files.write(external.resolve("metadata.bin"), byteArrayOf(1, 2, 3)).toRealPath()
        try {
            Files.createSymbolicLink(classes.resolve("metadata.bin"), target)
        } catch (unavailable: UnsupportedOperationException) {
            assumeNoException(unavailable)
            return
        } catch (unavailable: java.nio.file.FileSystemException) {
            assumeNoException(unavailable)
            return
        }
        val local = LocalFileSystem.getInstance()
        val library = local.refreshAndFindFileByNioFile(classes.parent)!!
        libraries += PsiTestUtil.addProjectLibrary(module, "Linked metadata", listOf(library), emptyList())
        val targetFile = local.refreshAndFindFileByNioFile(target)!!
        val file = source()
        val current = analysis()
        current.startAnalysis()
        awaitCurrent(current, file)
        await("First cache write") { Files.isRegularFile(cachePath()) }

        WriteCommandAction.runWriteCommandAction(project) { targetFile.setBinaryContent(byteArrayOf(1, 2, 4)) }

        assertFalse("External target change must invalidate the published snapshot", current.hasCurrentAnalysis(file))
        awaitCurrent(current, file)
        assertTrue("Changed linked classpath must rebuild source graphs", file.virtualFile.url in current.lastResolvedFiles)
        assertEquals(setOf(Dispatcher.IO), lastCall(current, file).summary.dispatchers.known)
    }

    private fun source(): KtFile {
        val file = myFixture.addFileToProject("Saved.kt", """
            import kotlinx.coroutines.*
            private suspend fun load(): Int = withContext(Dispatchers.IO) { 42 }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """.trimIndent()) as KtFile
        FileDocumentManager.getInstance().saveAllDocuments()
        return file
    }

    private fun analysis(): DispatcherAnalysisService {
        val job = SupervisorJob()
        lifetimes += job
        return DispatcherAnalysisService(project, CoroutineScope(job + Dispatchers.Default)).apply {
            cacheDirectoryOverride = cacheProject
        }
    }

    private fun stopLastService() {
        val job = lifetimes.last()
        job.cancel()
        await("Cache service stopped") { job.isCompleted }
    }

    private fun cachePath() = cacheProject.resolve("build/dispatcher-analyzer/analysis-cache.bin")

    private fun awaitCurrent(analysis: DispatcherAnalysisService, file: KtFile) =
        await("Current saved analysis") { analysis.hasCurrentAnalysis(file) }

    private fun lastCall(analysis: DispatcherAnalysisService, file: KtFile): BadgeResult =
        ReadAction.compute<BadgeResult, RuntimeException> {
            analysis.requestAnalysis(file).calls.getValue(file.text.lastIndexOf("load()") + "load()".length)
        }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertTrue(description, condition())
    }
}
