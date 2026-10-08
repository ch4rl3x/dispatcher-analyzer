package de.charlex.dispatcher.analysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import de.charlex.dispatcher.model.Dispatcher
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

class SelectionAnalysisTest : BasePlatformTestCase() {
    override fun getProjectDescriptor() = LightJavaCodeInsightFixtureTestCase.JAVA_21

    override fun setUp() {
        super.setUp()
        System.getProperty("dispatcher.fixture.libraries").split(File.pathSeparator).forEach { path ->
            val library = File(path)
            PsiTestUtil.addLibrary(module, library.nameWithoutExtension, library.parent, library.name)
        }
    }

    fun testExplicitWithContextMarksCalleeSelection() {
        val file = source("private suspend fun load() = withContext(Dispatchers.IO) { 42 }")
        val badge = loadCall(file)
        assertTrue(badge.summary.setsDispatcher)
        assertEquals(setOf(Dispatcher.IO), badge.summary.dispatchers.known)
    }

    fun testMixedPartialWorkPreservesSelectionFlag() {
        val file = source("private suspend fun load() { println(1); withContext(Dispatchers.IO) { println(2) } }")
        val badge = loadCall(file)
        assertTrue(badge.summary.setsDispatcher)
        assertEquals(setOf(Dispatcher.Main, Dispatcher.IO), badge.summary.dispatchers.known)
        assertTrue(badge.summary.badgeSegments().all { it.partial })
    }

    fun testKnownInheritedDispatcherDoesNotCountAsSelection() {
        val file = source("private suspend fun load() { delay(1) }")
        val result = analyze(file)
        assertFalse(lastCall(file, result, "load()").summary.setsDispatcher)
        assertFalse(lastCall(file, result, "delay(1)").summary.setsDispatcher)
        assertEquals(setOf(Dispatcher.Main), lastCall(file, result, "load()").summary.dispatchers.known)
    }

    fun testNonDispatcherContextElementsDoNotCountAsSelection() {
        val file = source("private suspend fun load() = withContext(NonCancellable + CoroutineName(\"work\")) { 42 }")
        assertFalse(loadCall(file).summary.setsDispatcher)
    }

    fun testUnknownExplicitDispatcherStillCountsAsSelection() {
        val file = myFixture.addFileToProject("Selection.kt", """
            import kotlinx.coroutines.*
            private suspend fun load(dispatcher: CoroutineDispatcher) = withContext(dispatcher) { 42 }
            fun start(dispatcher: CoroutineDispatcher) { CoroutineScope(Dispatchers.Main).launch { load(dispatcher) } }
        """.trimIndent()) as KtFile
        val badge = lastCall(file, analyze(file), "load(dispatcher)")
        assertTrue(badge.summary.setsDispatcher)
        assertTrue(badge.summary.dispatchers.hasUnknown)
    }

    fun testSynchronousCalleeSelectionPropagatesThroughInheritedWrapper() {
        val file = source("""
            private suspend fun inner() = withContext(Dispatchers.IO) { 42 }
            private suspend fun load(): Int = withContext(NonCancellable) { inner() }
        """)
        assertTrue(loadCall(file).summary.setsDispatcher)
    }

    fun testAsyncChildSelectionDoesNotMarkParentCall() {
        val file = source("""
            private suspend fun load() = coroutineScope {
                launch(Dispatchers.IO) { withContext(Dispatchers.Default) { delay(1) } }
                println("parent")
            }
        """)
        val result = analyze(file)
        assertFalse(lastCall(file, result, "load()").summary.setsDispatcher)
        val childCall = "withContext(Dispatchers.Default)"
        assertFalse(result.calls.containsKey(file.text.indexOf(childCall) + childCall.length))
        assertEquals(setOf(Dispatcher.Default), lastCall(file, result, "delay(1)").summary.dispatchers.known)
    }

    fun testExplicitRunBlockingCountsAsSynchronousSelection() {
        val file = source("private suspend fun load() = runBlocking(Dispatchers.IO) { 42 }")
        val badge = loadCall(file)
        assertTrue(badge.summary.setsDispatcher)
        assertEquals(setOf(Dispatcher.IO), badge.summary.dispatchers.known)
    }

    fun testRunBlockingWithoutDispatcherDoesNotClaimSelection() {
        val file = source("private suspend fun load() = runBlocking(NonCancellable) { 42 }")
        assertFalse(loadCall(file).summary.setsDispatcher)
    }

    fun testCustomDispatcherSelectionQualifies() {
        val file = source("""
            private val worker = newSingleThreadContext("worker")
            private suspend fun load() = withContext(worker) { 42 }
        """)
        val badge = loadCall(file)
        assertTrue(badge.summary.setsDispatcher)
        assertTrue(badge.summary.dispatchers.known.single() is Dispatcher.Custom)
    }

    fun testStaleResultsDoNotClaimSelection() {
        val file = source("private suspend fun load() = withContext(Dispatchers.IO) { 42 }")
        val dependency = myFixture.addFileToProject("Dependency.kt", "val dependency = 1") as KtFile
        assertTrue(loadCall(file).summary.setsDispatcher)
        WriteCommandAction.runWriteCommandAction(project) {
            PsiDocumentManager.getInstance(project).getDocument(dependency)!!.setText("val dependency = 2")
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val stale = ReadAction.compute<FileAnalysis, RuntimeException> {
            project.service<DispatcherAnalysisService>().requestAnalysis(file)
        }
        assertFalse(stale.calls.isEmpty())
        assertTrue(stale.calls.values.all { it.summary.dispatchers.hasUnknown && !it.summary.setsDispatcher })
    }

    private fun source(body: String) = myFixture.addFileToProject("Selection.kt", """
        import kotlinx.coroutines.*
        ${body.trimIndent()}
        fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
    """.trimIndent()) as KtFile

    private fun loadCall(file: KtFile) = lastCall(file, analyze(file), "load()")

    private fun lastCall(file: KtFile, result: FileAnalysis, text: String) =
        result.calls.getValue(file.text.lastIndexOf(text) + text.length)

    private fun analyze(file: KtFile) = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread<FileAnalysis> {
            ReadAction.compute<FileAnalysis, RuntimeException> { project.service<DispatcherAnalysisService>().analyze(file) }
        },
    )
}
