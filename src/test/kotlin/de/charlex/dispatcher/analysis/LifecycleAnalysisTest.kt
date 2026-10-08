package de.charlex.dispatcher.analysis

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import de.charlex.dispatcher.editor.DispatcherHintSettings
import de.charlex.dispatcher.editor.DispatcherSettings
import de.charlex.dispatcher.model.Dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

class LifecycleAnalysisTest : BasePlatformTestCase() {
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

    fun testIndexingDefersResolutionAndResumesWhenSmart() {
        val file = source("private suspend fun load() = withContext(Dispatchers.IO) { 42 }")
        val analysis = project.service<DispatcherAnalysisService>()
        val indexing = DumbModeTestUtils.startEternalDumbModeTask(project)
        try {
            val unavailable = read { analysis.analyze(file) }
            assertTrue(unavailable.declarations.isEmpty())
            assertTrue(unavailable.calls.isEmpty())
            read { analysis.requestAnalysis(file) }
            dispatchPastDebounce()
            assertFalse(analysis.hasCurrentAnalysis(file))
        } finally {
            DumbModeTestUtils.endEternalDumbModeTaskAndWaitForSmartMode(project, indexing)
        }
        PlatformTestUtil.waitWithEventsDispatching("Analysis after indexing", { analysis.hasCurrentAnalysis(file) }, 30)
        assertEquals(1, read { analysis.requestAnalysis(file) }.declarations.size)
    }

    fun testMalformedBodyCannotKeepPreviousCompleteCoverage() {
        val file = source("private suspend fun load() = withContext(Dispatchers.IO) { 42 }")
        val analysis = project.service<DispatcherAnalysisService>()
        val previous = analyze(analysis, file)
        val originalOffset = file.text.lastIndexOf("load()") + "load()".length
        assertEquals(setOf(Dispatcher.IO), previous.calls.getValue(originalOffset).summary.dispatchers.known)

        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
            document.setText(document.text.replace(
                "private suspend fun load() = withContext(Dispatchers.IO) { 42 }",
                "private suspend fun load() { withContext(Dispatchers.IO) { 42 }; val broken = }",
            ))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val revised = analyze(analysis, file)
        val offset = file.text.lastIndexOf("load()") + "load()".length
        val call = revised.calls[offset]
        if (call != null) assertTrue(call.summary.dispatchers.hasUnknown)
        else assertTrue(revised.declarations.values.all { it.summary.dispatchers.hasUnknown })
        assertNotSame(previous, revised)
    }

    fun testMissingBodyHasUnknownExecutionEffects() {
        val file = source("private suspend fun load()")
        val result = analyze(project.service(), file)
        val offset = file.text.lastIndexOf("load()") + "load()".length
        assertTrue(result.calls.getValue(offset).summary.dispatchers.hasUnknown)
    }

    fun testCancellationPropagatesWithoutCachingAResult() {
        val file = source("private suspend fun load() = withContext(Dispatchers.IO) { 42 }")
        val analysis = project.service<DispatcherAnalysisService>()
        val cancelled = PlatformTestUtil.waitForFuture(
            ApplicationManager.getApplication().executeOnPooledThread<Boolean> {
                read {
                    val indicator = ProgressIndicatorBase()
                    try {
                        ProgressManager.getInstance().runProcess(Runnable {
                            indicator.cancel()
                            analysis.analyze(file)
                        }, indicator)
                        false
                    } catch (_: ProcessCanceledException) {
                        true
                    }
                }
            },
        )
        assertTrue(cancelled)
        assertFalse(analysis.hasCurrentAnalysis(file))
    }

    fun testDisposingServiceLifetimeCancelsPendingWork() {
        val file = source("private suspend fun load() = withContext(Dispatchers.IO) { 42 }")
        val lifetime = Disposer.newDisposable("Lifecycle analysis test")
        Disposer.register(testRootDisposable, lifetime)
        val job = SupervisorJob()
        Disposer.register(lifetime, Disposable { job.cancel() })
        val analysis = DispatcherAnalysisService(project, CoroutineScope(job + Dispatchers.Default))
        read { analysis.requestAnalysis(file) }
        Disposer.dispose(lifetime)
        PlatformTestUtil.waitWithEventsDispatching("Analysis scope disposal", { job.isCompleted }, 10)
        dispatchPastDebounce()
        assertTrue(job.isCancelled)
        assertFalse(analysis.hasCurrentAnalysis(file))
        assertFalse(analysis.analysisStatus == "Up to date")
    }

    private fun source(declaration: String) = myFixture.addFileToProject("Lifecycle.kt", """
        import kotlinx.coroutines.*
        $declaration
        fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
    """.trimIndent()) as KtFile

    private fun analyze(analysis: DispatcherAnalysisService, file: KtFile): FileAnalysis = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread<FileAnalysis> { read { analysis.analyze(file) } },
    )

    private fun <T> read(block: () -> T): T = ReadAction.compute<T, RuntimeException>(block)

    private fun dispatchPastDebounce() {
        val deadline = System.nanoTime() + 1_100_000_000
        while (System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }
}
