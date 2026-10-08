package de.charlex.dispatcher.analysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import de.charlex.dispatcher.editor.DispatcherHintSettings
import de.charlex.dispatcher.editor.DispatcherSettings
import de.charlex.dispatcher.model.Dispatcher
import de.charlex.dispatcher.model.DispatcherOrigin
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.io.File

class OriginAnalysisTest : BasePlatformTestCase() {
    private lateinit var previousSettings: DispatcherHintSettings

    override fun getProjectDescriptor() = LightJavaCodeInsightFixtureTestCase.JAVA_21

    override fun setUp() {
        super.setUp()
        previousSettings = service<DispatcherSettings>().state
        service<DispatcherSettings>().update(false, false)
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

    fun testIncomingAndCalleeEffectOriginsStaySeparate() {
        val file = source("""
            private suspend fun load() = withContext(Dispatchers.IO) { 42 }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        val result = analyze(file)
        assertOrigin(file, declaration(file, result, "load"), Dispatcher.Main, "Dispatchers.Main")
        assertOrigin(file, call(file, result, "load()"), Dispatcher.IO, "Dispatchers.IO")
        assertEquals(setOf(Dispatcher.Main), declaration(file, result, "load").summary.dispatchers.origins.keys)
        assertEquals(setOf(Dispatcher.IO), call(file, result, "load()").summary.dispatchers.origins.keys)
    }

    fun testOneDispatcherCollectsDistinctOriginsAcrossCallers() {
        val file = source("""
            private suspend fun load() = 42
            fun first() { CoroutineScope(Dispatchers.Main).launch { load() } }
            fun second() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        val origins = declaration(file, analyze(file), "load").summary.dispatchers.origins.getValue(Dispatcher.Main)
        assertEquals(2, origins.size)
        assertEquals(setOf(file.text.indexOf("Dispatchers.Main"), file.text.lastIndexOf("Dispatchers.Main")), origins.map { it.offset }.toSet())
        assertTrue(origins.all { it.fileUrl == file.virtualFile.url })
    }

    fun testCrossFileAliasNavigatesToItsInitializer() {
        val alias = myFixture.addFileToProject("Dispatchers.kt", """
            import kotlinx.coroutines.Dispatchers
            val worker = Dispatchers.IO
        """.trimIndent()) as KtFile
        val file = source("""
            private suspend fun load() = withContext(worker) { 42 }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        assertOrigin(alias, call(file, analyze(file), "load()"), Dispatcher.IO, "Dispatchers.IO")
    }

    fun testOverridingContextDropsDisplacedDispatcherOrigins() {
        val file = source("""
            private suspend fun load() = withContext(Dispatchers.Main + CoroutineName("work") + Dispatchers.IO) { 42 }
            fun start() { CoroutineScope(Dispatchers.Default).launch { load() } }
        """)
        val badge = call(file, analyze(file), "load()")
        assertEquals(setOf(Dispatcher.IO, Dispatcher.Default), badge.summary.dispatchers.known)
        assertEquals(setOf(Dispatcher.IO, Dispatcher.Default), badge.summary.dispatchers.origins.keys)
        assertOrigin(file, badge, Dispatcher.IO, "Dispatchers.IO")
        assertOrigin(file, badge, Dispatcher.Default, "Dispatchers.Default")
    }

    fun testImplicitBuilderDefaultAndMainScopeHaveCreationOrigins() {
        val file = source("""
            private suspend fun loadDefault() = 42
            private suspend fun loadMain() = 42
            fun start() {
                GlobalScope.launch { loadDefault() }
                MainScope().launch { loadMain() }
            }
        """)
        val result = analyze(file)
        assertOrigin(file, declaration(file, result, "loadDefault"), Dispatcher.Default, "launch { loadDefault() }")
        assertOrigin(file, declaration(file, result, "loadMain"), Dispatcher.Main, "MainScope()")
    }

    fun testCustomFactoryKeepsItsInitializerOrigin() {
        val file = source("""
            import java.util.concurrent.Executors
            private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            private suspend fun load() = withContext(worker) { 42 }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        val badge = call(file, analyze(file), "load()")
        val dispatcher = badge.summary.dispatchers.known.single()
        assertTrue(dispatcher is Dispatcher.Custom)
        assertOrigin(file, badge, dispatcher, "Executors.newSingleThreadExecutor().asCoroutineDispatcher()")
    }

    fun testCustomObjectOriginPointsToItsContextUse() {
        val file = source("""
            import kotlin.coroutines.CoroutineContext
            object Worker : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
            }
            private suspend fun load() = withContext(Worker) { 42 }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        val badge = call(file, analyze(file), "load()")
        val dispatcher = badge.summary.dispatchers.known.single()
        assertTrue(dispatcher is Dispatcher.Custom)
        val origin = badge.summary.dispatchers.origins.getValue(dispatcher).single()
        assertEquals(file.text.indexOf("withContext(Worker)") + "withContext(".length, origin.offset)
        assertEquals("Worker", origin.description)
    }

    fun testInheritedOriginsPropagateThroughCalls() {
        val file = source("""
            private suspend fun leaf() = 42
            private suspend fun middle() = leaf()
            fun start() { CoroutineScope(Dispatchers.Main).launch { middle() } }
        """)
        val result = analyze(file)
        assertOrigin(file, declaration(file, result, "leaf"), Dispatcher.Main, "Dispatchers.Main")
        assertOrigin(file, call(file, result, "leaf()"), Dispatcher.Main, "Dispatchers.Main")
    }

    fun testUnknownAndInvalidatedSnapshotsExposeNoNavigationSources() {
        val file = source("""
            suspend fun unused() { delay(1) }
            private suspend fun load() = 42
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        val dependency = myFixture.addFileToProject("Dependency.kt", "val dependency = 1") as KtFile
        val analysis = project.service<DispatcherAnalysisService>()
        val current = analyze(file)
        assertTrue(call(file, current, "delay(1)").summary.dispatchers.origins.isEmpty())
        assertTrue(analysis.isCurrentAnalysis(file.virtualFile.url, current))
        assertFalse(analysis.isCurrentAnalysis(file.virtualFile.url, current.copy()))
        WriteCommandAction.runWriteCommandAction(project) {
            PsiDocumentManager.getInstance(project).getDocument(dependency)!!.setText("val dependency = 2")
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val pending = ReadAction.compute<FileAnalysis, RuntimeException> { analysis.requestAnalysis(file) }
        assertFalse(analysis.isCurrentAnalysis(file.virtualFile.url, current))
        assertFalse(analysis.isCurrentAnalysis(file.virtualFile.url, pending))
        assertEquals(1, pending.declarations.size)
        assertTrue((pending.declarations.values + pending.calls.values).all { it.summary.dispatchers.origins.isEmpty() })
    }

    private fun source(text: String) = myFixture.addFileToProject(
        "Origins.kt", "import kotlinx.coroutines.*\n${text.trimIndent()}",
    ) as KtFile

    private fun analyze(file: KtFile) = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread<FileAnalysis> {
            ReadAction.compute<FileAnalysis, RuntimeException> { project.service<DispatcherAnalysisService>().analyze(file) }
        },
    )

    private fun declaration(file: KtFile, result: FileAnalysis, name: String): BadgeResult {
        val function = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).single { it.name == name }
        return result.declarations.getValue(function.textRange.startOffset)
    }

    private fun call(file: KtFile, result: FileAnalysis, text: String): BadgeResult =
        result.calls.getValue(file.text.lastIndexOf(text) + text.length)

    private fun assertOrigin(file: KtFile, badge: BadgeResult, dispatcher: Dispatcher, text: String) {
        val offset = file.text.indexOf(text)
        assertTrue("Missing fixture origin: $text", offset >= 0)
        val expected = DispatcherOrigin(
            file.virtualFile.url, offset, file.text.take(offset).count { it == '\n' } + 1, text,
        )
        assertEquals(setOf(expected), badge.summary.dispatchers.origins[dispatcher])
    }
}
