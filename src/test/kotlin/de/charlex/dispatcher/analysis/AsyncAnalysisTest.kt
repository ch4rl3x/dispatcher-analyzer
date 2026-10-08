package de.charlex.dispatcher.analysis

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import de.charlex.dispatcher.model.Dispatcher
import de.charlex.dispatcher.editor.DispatcherSettings
import de.charlex.dispatcher.editor.DispatcherHintSettings
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

class AsyncAnalysisTest : BasePlatformTestCase() {
    private lateinit var previousSettings: DispatcherHintSettings

    override fun getProjectDescriptor() = LightJavaCodeInsightFixtureTestCase.JAVA_21

    override fun setUp() {
        super.setUp()
        previousSettings = service<DispatcherSettings>().state
        service<DispatcherSettings>().update(false, true)
        System.getProperty("dispatcher.fixture.libraries").split(File.pathSeparator).forEach { path ->
            val library = File(path)
            PsiTestUtil.addLibrary(module, library.nameWithoutExtension, library.parent, library.name)
        }
    }

    override fun tearDown() {
        try {
            service<DispatcherSettings>().update(previousSettings.showCalls, previousSettings.automaticAnalysis)
        } finally {
            super.tearDown()
        }
    }

    fun testManualModeWaitsForExplicitRunsAndMarksEditsStale() {
        service<DispatcherSettings>().update(false, false)
        val file = myFixture.addFileToProject("Manual.kt", """
            import kotlinx.coroutines.*
            private suspend fun load() = withContext(Dispatchers.IO) { 42 }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """.trimIndent()) as KtFile
        val analysis = project.service<DispatcherAnalysisService>()
        val pending = request(analysis, file)
        assertTrue(pending.declarations.values.all { it.tooltip.contains("Run Analyze project") })
        dispatchPastDebounce()
        assertFalse(analysis.hasCurrentAnalysis(file))

        analysis.runAnalysisNow()
        awaitCurrent(analysis, file)
        val callOffset = file.text.lastIndexOf("load()") + "load()".length
        assertEquals(setOf(Dispatcher.IO), request(analysis, file).calls.getValue(callOffset).summary.dispatchers.known)

        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
            document.setText(document.text.replace("Dispatchers.IO", "Dispatchers.Default"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val stale = request(analysis, file)
        assertTrue(stale.declarations.values.all { it.summary.dispatchers.hasUnknown })
        assertTrue(stale.calls.isEmpty())
        dispatchPastDebounce()
        assertEquals("Out of date", analysis.analysisStatus)
        assertFalse(analysis.hasCurrentAnalysis(file))

        analysis.runAnalysisNow()
        awaitCurrent(analysis, file)
        val revisedOffset = file.text.lastIndexOf("load()") + "load()".length
        assertEquals(setOf(Dispatcher.Default), request(analysis, file).calls.getValue(revisedOffset).summary.dispatchers.known)
    }

    fun testManualProjectRunDoesNotRequireAnOpenEditor() {
        service<DispatcherSettings>().update(false, false)
        val file = myFixture.addFileToProject("Closed.kt", "private suspend fun load() = 42") as KtFile
        val analysis = project.service<DispatcherAnalysisService>()
        analysis.runAnalysisNow()
        awaitCurrent(analysis, file)
        assertEquals(1, request(analysis, file).declarations.size)
    }

    fun testSwitchingToManualCancelsQueuedAutomaticAnalysis() {
        val file = myFixture.addFileToProject("Mode.kt", "private suspend fun load() = 42") as KtFile
        val analysis = project.service<DispatcherAnalysisService>()
        request(analysis, file)
        service<DispatcherSettings>().update(true, false)
        dispatchPastDebounce()
        assertFalse(analysis.hasCurrentAnalysis(file))
        assertTrue(service<DispatcherSettings>().showCalls)

        service<DispatcherSettings>().update(true, true)
        awaitCurrent(analysis, file)
        assertTrue(service<DispatcherSettings>().showCalls)
    }

    private fun awaitCurrent(analysis: DispatcherAnalysisService, file: KtFile) =
        PlatformTestUtil.waitWithEventsDispatching("Current background snapshot", { analysis.hasCurrentAnalysis(file) }, 30)

    private fun dispatchPastDebounce() {
        val deadline = System.nanoTime() + 1_100_000_000
        PlatformTestUtil.waitWithEventsDispatching("Past automatic debounce", { System.nanoTime() >= deadline }, 5)
    }

    fun testRequestsAreDeferredAndSupersededCodeNeverStaysKnown() {
        val file = myFixture.addFileToProject("Async.kt", """
            import kotlinx.coroutines.*
            private suspend fun load() = withContext(Dispatchers.IO) { 42 }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """.trimIndent()) as KtFile
        val service = project.service<DispatcherAnalysisService>()
        val pending = request(service, file)
        assertTrue(pending.declarations.values.all { it.summary.dispatchers.hasUnknown })

        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
            document.setText(document.text.replace("Dispatchers.IO", "Dispatchers.Default"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val revised = request(service, file)
        assertTrue(revised.declarations.values.all { it.summary.dispatchers.hasUnknown })
        val callOffset = file.text.lastIndexOf("load()") + "load()".length
        PlatformTestUtil.waitWithEventsDispatching("Current asynchronous analysis", {
            request(service, file).calls[callOffset]?.summary?.dispatchers?.known == setOf(Dispatcher.Default)
        }, 20)
        val current = request(service, file)
        assertEquals(setOf(Dispatcher.Default), current.calls.getValue(callOffset).summary.dispatchers.known)
        assertFalse(current.calls.getValue(callOffset).summary.dispatchers.hasUnknown)
        assertSame(current, request(service, file))
    }

    fun testDeletedFileDoesNotPublishStaleResults() {
        val file = myFixture.addFileToProject("Deleted.kt", "private suspend fun load() = 42") as KtFile
        val service = project.service<DispatcherAnalysisService>()
        request(service, file)
        WriteCommandAction.runWriteCommandAction(project) { file.delete() }
        assertTrue(request(service, file).declarations.isEmpty())
    }

    private fun request(service: DispatcherAnalysisService, file: KtFile) =
        ReadAction.compute<FileAnalysis, RuntimeException> { service.requestAnalysis(file) }
}
