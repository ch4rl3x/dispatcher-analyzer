package de.charlex.dispatcher.analysis

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class IncrementalAnalysisTest : BasePlatformTestCase() {
    private lateinit var lifetime: Job
    private lateinit var analysis: DispatcherAnalysisService
    private lateinit var cacheProject: Path
    private var started = false

    override fun getProjectDescriptor() = LightJavaCodeInsightFixtureTestCase.JAVA_21

    override fun setUp() {
        super.setUp()
        System.getProperty("dispatcher.fixture.libraries").split(File.pathSeparator).forEach { path ->
            val library = File(path)
            PsiTestUtil.addLibrary(module, library.nameWithoutExtension, library.parent, library.name)
        }
        cacheProject = Files.createTempDirectory("dispatcher-incremental-cache")
        lifetime = SupervisorJob()
        analysis = DispatcherAnalysisService(project, CoroutineScope(lifetime + Dispatchers.Default)).apply {
            cacheDirectoryOverride = cacheProject
        }
    }

    override fun tearDown() {
        try {
            lifetime.cancel()
            await("Incremental analysis disposed") { lifetime.isCompleted }
            cacheProject.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testBodyChangeResolvesDependentsAndReusesUnrelatedGraph() {
        val callee = file("Callee.kt", "suspend fun load(): Int = withContext(Dispatchers.IO) { 42 }")
        val caller = file("Caller.kt", "suspend fun bridge(): Int = load()")
        val entry = file("Entry.kt", "fun start() { CoroutineScope(Dispatchers.Main).launch { bridge() } }")
        val unrelated = file("Unrelated.kt", "private suspend fun unrelated(): Int = 1")
        analyze(entry)
        val unrelatedResult = analyze(unrelated)
        replace(callee, callee.text.replace("Dispatchers.IO", "Dispatchers.Default"))
        val result = analyze(entry)
        assertEquals(setOf(callee.virtualFile.url, caller.virtualFile.url, entry.virtualFile.url), analysis.lastResolvedFiles)
        assertEquals(setOf(Dispatcher.Default), lastCall(entry, result, "bridge()").summary.dispatchers.known)
        assertSame(unrelatedResult, analyze(unrelated))
    }

    fun testAliasChangeRefreshesOnlyDependentGraphsAndOrigins() {
        val aliases = file("Dispatchers.kt", "val worker: CoroutineDispatcher = Dispatchers.IO")
        val callee = file("Work.kt", "suspend fun load(): Int = withContext(worker) { 42 }")
        val caller = file("Caller.kt", "fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }")
        val unrelated = file("Unrelated.kt", "private suspend fun unrelated(): Int = 1")
        analyze(caller)
        replace(aliases, aliases.text.replace("Dispatchers.IO", "Dispatchers.Default"))
        val result = analyze(caller)
        assertEquals(setOf(aliases.virtualFile.url, callee.virtualFile.url, caller.virtualFile.url), analysis.lastResolvedFiles)
        assertFalse(unrelated.virtualFile.url in analysis.lastResolvedFiles)
        val origins = lastCall(caller, result, "load()").summary.dispatchers.origins.getValue(Dispatcher.Default)
        assertEquals(setOf(aliases.virtualFile.url), origins.map { it.fileUrl }.toSet())
        assertEquals(setOf(aliases.text.indexOf("Dispatchers.Default")), origins.map { it.offset }.toSet())
    }

    fun testRemovedCallDropsIncomingEvidenceWithoutKeepingOldFacts() {
        val callee = file("Work.kt", "suspend fun load(): Int = 42")
        val caller = file("Caller.kt", "fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }")
        val unrelated = file("Unrelated.kt", "private suspend fun unrelated(): Int = 1")
        assertEquals(1, analyze(callee).declarations.size)
        replace(caller, caller.text.replace("load()", "println(1)"))
        assertTrue(analyze(callee).declarations.isEmpty())
        assertEquals(setOf(caller.virtualFile.url), analysis.lastResolvedFiles)
        assertFalse(unrelated.virtualFile.url in analysis.lastResolvedFiles)
    }

    fun testNewDeclarationConservativelyRebuildsAllGraphs() {
        val changed = file("Changed.kt", "suspend fun load(): Int = 42")
        val unrelated = file("Unrelated.kt", "private suspend fun unrelated(): Int = 1")
        analyze(changed)
        replace(changed, changed.text + "\nsuspend fun added(): Int = 1")
        analyze(changed)
        assertEquals(setOf(changed.virtualFile.url, unrelated.virtualFile.url), analysis.lastResolvedFiles)
    }

    fun testInferredReturnTypeChangeTriggersStructuralRebuild() {
        val changed = file("Changed.kt", "suspend fun load() = 42")
        val unrelated = file("Unrelated.kt", "private suspend fun unrelated(): Int = 1")
        analyze(changed)
        replace(changed, changed.text.replace("= 42", "= \"changed\""))
        analyze(changed)
        assertEquals(setOf(changed.virtualFile.url, unrelated.virtualFile.url), analysis.lastResolvedFiles)
    }

    private fun file(name: String, source: String) = myFixture.addFileToProject(name, "import kotlinx.coroutines.*\n$source") as KtFile

    private fun replace(file: KtFile, text: String) = WriteCommandAction.runWriteCommandAction(project) {
        PsiDocumentManager.getInstance(project).getDocument(file)!!.setText(text)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun lastCall(file: KtFile, result: FileAnalysis, text: String) = result.calls.getValue(file.text.lastIndexOf(text) + text.length)

    private fun analyze(file: KtFile): FileAnalysis {
        FileDocumentManager.getInstance().saveAllDocuments()
        if (!started) {
            started = true
            analysis.startAnalysis()
        }
        await("Current saved incremental analysis") { analysis.hasCurrentAnalysis(file) }
        await("Incremental cache written") { Files.isRegularFile(cacheProject.resolve("build/dispatcher-analyzer/analysis-cache.bin")) }
        return ReadAction.compute<FileAnalysis, RuntimeException> { analysis.requestAnalysis(file) }
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
