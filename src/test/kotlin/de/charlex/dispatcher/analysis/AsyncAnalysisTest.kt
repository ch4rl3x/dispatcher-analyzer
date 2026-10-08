package de.charlex.dispatcher.analysis

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import de.charlex.dispatcher.model.Dispatcher
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

class AsyncAnalysisTest : BasePlatformTestCase() {
    override fun getProjectDescriptor() = LightJavaCodeInsightFixtureTestCase.JAVA_21

    override fun setUp() {
        super.setUp()
        System.getProperty("dispatcher.fixture.libraries").split(File.pathSeparator).forEach { path ->
            val library = File(path)
            PsiTestUtil.addLibrary(module, library.nameWithoutExtension, library.parent, library.name)
        }
    }

    fun testStartupAnalyzesImportedFilesWithoutAnEditor() {
        val file = myFixture.addFileToProject("Imported.kt", "private suspend fun load() = 42\nsuspend fun entry() = load()") as KtFile
        val analysis = project.service<DispatcherAnalysisService>()

        FileDocumentManager.getInstance().saveAllDocuments()
        assertFalse(analysis.hasCurrentAnalysis(file))
        analysis.startAnalysis()
        awaitCurrent(analysis, file)

        assertEquals(1, request(analysis, file).declarations.size)
    }

    fun testUnsavedTypingInvalidatesButOnlySaveStartsAnalysis() {
        val file = myFixture.addFileToProject("Saved.kt", """
            import kotlinx.coroutines.*
            private suspend fun load() = withContext(Dispatchers.IO) { 42 }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """.trimIndent()) as KtFile
        val analysis = project.service<DispatcherAnalysisService>()
        FileDocumentManager.getInstance().saveAllDocuments()
        analysis.startAnalysis()
        awaitCurrent(analysis, file)

        replace(file, "Dispatchers.IO", "Dispatchers.Default")
        assertFalse(analysis.hasCurrentAnalysis(file))
        val stale = request(analysis, file)
        assertTrue(stale.calls.values.none { Dispatcher.Default in it.summary.dispatchers.known })
        waitForNoImplicitRestart(analysis)
        assertFalse(analysis.hasCurrentAnalysis(file))

        save(file)
        awaitCurrent(analysis, file)
        val offset = file.text.lastIndexOf("load()") + "load()".length
        assertEquals(setOf(Dispatcher.Default), request(analysis, file).calls.getValue(offset).summary.dispatchers.known)
    }

    fun testSupersededSaveAndDeletedFileCannotPublishStaleResults() {
        val file = myFixture.addFileToProject("Superseded.kt", """
            import kotlinx.coroutines.*
            private suspend fun load() = withContext(Dispatchers.IO) { 42 }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """.trimIndent()) as KtFile
        val analysis = project.service<DispatcherAnalysisService>()
        FileDocumentManager.getInstance().saveAllDocuments()
        analysis.startAnalysis()
        awaitCurrent(analysis, file)

        replace(file, "Dispatchers.IO", "Dispatchers.Default")
        save(file)
        replace(file, "Dispatchers.Default", "Dispatchers.Main")
        save(file)
        awaitCurrent(analysis, file)
        val offset = file.text.lastIndexOf("load()") + "load()".length
        assertEquals(setOf(Dispatcher.Main), request(analysis, file).calls.getValue(offset).summary.dispatchers.known)

        replace(file, "Dispatchers.Main", "Dispatchers.IO")
        save(file)
        WriteCommandAction.runWriteCommandAction(project) { file.delete() }
        assertFalse(analysis.hasCurrentAnalysis(file))
        assertTrue(request(analysis, file).declarations.isEmpty())
        assertTrue(request(analysis, file).calls.isEmpty())
    }

    private fun awaitCurrent(analysis: DispatcherAnalysisService, file: KtFile) =
        PlatformTestUtil.waitWithEventsDispatching("Current saved project snapshot", { analysis.hasCurrentAnalysis(file) }, 30)

    private fun waitForNoImplicitRestart(analysis: DispatcherAnalysisService) {
        val deadline = System.nanoTime() + 1_100_000_000
        while (System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertEquals("Out of date", analysis.analysisStatus)
    }

    private fun save(file: KtFile) {
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        FileDocumentManager.getInstance().saveDocument(document)
    }

    private fun replace(file: KtFile, before: String, after: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
            document.setText(document.text.replace(before, after))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }

    private fun request(analysis: DispatcherAnalysisService, file: KtFile) =
        ReadAction.compute<FileAnalysis, RuntimeException> { analysis.requestAnalysis(file) }
}
