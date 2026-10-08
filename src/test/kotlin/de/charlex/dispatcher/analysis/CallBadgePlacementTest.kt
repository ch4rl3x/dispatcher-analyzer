package de.charlex.dispatcher.analysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import de.charlex.dispatcher.model.Dispatcher
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

class CallBadgePlacementTest : BasePlatformTestCase() {
    override fun getProjectDescriptor() = LightJavaCodeInsightFixtureTestCase.JAVA_21

    override fun setUp() {
        super.setUp()
        System.getProperty("dispatcher.fixture.libraries").split(File.pathSeparator).forEach { path ->
            val library = File(path)
            PsiTestUtil.addLibrary(module, library.nameWithoutExtension, library.parent, library.name)
        }
    }

    fun testDirectStandardDispatchersHideCallBadgesAndPreserveWrapperEffects() {
        val dispatchers = listOf(
            "Dispatchers.Main" to Dispatcher.Main,
            "Dispatchers.Main.immediate" to Dispatcher.Main,
            "Dispatchers.IO" to Dispatcher.IO,
            "Dispatchers.Default" to Dispatcher.Default,
            "Dispatchers.Unconfined" to Dispatcher.Unconfined,
            "(Dispatchers.IO)" to Dispatcher.IO,
        )
        val declarations = dispatchers.mapIndexed { index, (context, _) ->
            "private suspend fun work$index() = withContext($context) { delay(${index + 1}) }"
        }.joinToString("\n")
        val calls = dispatchers.indices.joinToString("; ") { "work$it()" }
        val file = source("""
            import kotlinx.coroutines.*
            $declarations
            suspend fun entry() { $calls }
        """)
        val result = analyze(file)

        val expectedOffsets = dispatchers.indices.flatMap { index ->
            listOf(offset(file, "delay(${index + 1})"), offset(file, "work$index()"))
        }.toSet()
        assertEquals("Only body and ordinary wrapper calls have badges", expectedOffsets, result.calls.keys)
        dispatchers.forEachIndexed { index, (_, dispatcher) ->
            val summary = result.calls.getValue(offset(file, "work$index()")).summary
            assertEquals(setOf(dispatcher), summary.dispatchers.known)
            assertFalse(summary.dispatchers.hasUnknown)
            assertTrue(summary.setsDispatcher)
            assertEquals(setOf(dispatcher), result.calls.getValue(offset(file, "delay(${index + 1})")).summary.dispatchers.known)
        }
    }

    fun testImportAliasesStillSuppressResolvedStandardDispatcherCalls() {
        val file = source("""
            import kotlinx.coroutines.Dispatchers as Workers
            import kotlinx.coroutines.withContext as switchContext
            private suspend fun work() = switchContext(Workers.IO) { 42 }
            suspend fun entry() = work()
        """)
        val result = analyze(file)

        assertEquals(setOf(offset(file, "work()")), result.calls.keys)
        assertEquals(setOf(Dispatcher.IO), result.calls.getValue(offset(file, "work()")).summary.dispatchers.known)
    }

    fun testValueAliasesAndUnknownDispatchersKeepBadgesAtCallHeaders() {
        val file = source("""
            import kotlinx.coroutines.*
            private val worker = Dispatchers.IO
            private suspend fun known() = withContext(worker) { delay(1) }
            private suspend fun injected(dispatcher: CoroutineDispatcher) = withContext(dispatcher) { delay(2) }
            suspend fun entry(dispatcher: CoroutineDispatcher) { known(); injected(dispatcher) }
        """)
        val result = analyze(file)

        listOf("withContext(worker)", "withContext(dispatcher)").forEach { header ->
            assertTrue("The call header remains visible: $header", result.calls.containsKey(offset(file, header)))
            val end = file.text.indexOf('}', file.text.indexOf(header)) + 1
            assertFalse("A trailing-lambda closing brace is not a badge anchor", result.calls.containsKey(end))
        }
        val alias = result.calls.getValue(offset(file, "withContext(worker)")).summary
        assertEquals(setOf(Dispatcher.IO), alias.dispatchers.known)
        assertFalse(alias.dispatchers.hasUnknown)
        assertTrue(alias.setsDispatcher)
        val unknown = result.calls.getValue(offset(file, "withContext(dispatcher)")).summary
        assertTrue(unknown.dispatchers.hasUnknown)
        assertTrue(unknown.setsDispatcher)
    }

    fun testLaunchAndAsyncStayHiddenWhileTheirBodyCallsKeepContexts() {
        val file = source("""
            import kotlinx.coroutines.*
            suspend fun start() = coroutineScope {
                launch(Dispatchers.IO) { delay(1) }
                async(Dispatchers.Default) { delay(2) }
            }
        """)
        val result = analyze(file)

        assertEquals(
            setOf(offset(file, "coroutineScope"), offset(file, "delay(1)"), offset(file, "delay(2)")),
            result.calls.keys,
        )
        assertEquals(setOf(Dispatcher.IO), result.calls.getValue(offset(file, "delay(1)")).summary.dispatchers.known)
        assertEquals(setOf(Dispatcher.Default), result.calls.getValue(offset(file, "delay(2)")).summary.dispatchers.known)
    }

    fun testShadowedWithContextKeepsItsOrdinaryFunctionBadge() {
        val file = source("""
            import kotlinx.coroutines.*
            private suspend fun withContext(context: CoroutineDispatcher, block: suspend () -> Int): Int = 42
            private suspend fun work() = withContext(Dispatchers.IO) { 42 }
            suspend fun entry() = work()
        """)
        val result = analyze(file)

        val header = offset(file, "withContext(Dispatchers.IO)")
        assertTrue(result.calls.containsKey(header))
        assertFalse(result.calls.getValue(header).summary.setsDispatcher)
        assertFalse(Dispatcher.IO in result.calls.getValue(header).summary.dispatchers.known)
        val end = file.text.indexOf('}', file.text.indexOf("withContext(Dispatchers.IO)")) + 1
        assertFalse(result.calls.containsKey(end))
    }

    fun testLookalikeDispatcherPropertyKeepsItsBadge() {
        val file = source("""
            import kotlinx.coroutines.withContext
            private object Dispatchers {
                val IO = kotlinx.coroutines.Dispatchers.Default
            }
            private suspend fun work() = withContext(Dispatchers.IO) { 42 }
            suspend fun entry() = work()
        """)
        val result = analyze(file)

        val header = result.calls.getValue(offset(file, "withContext(Dispatchers.IO)"))
        assertEquals(setOf(Dispatcher.Default), header.summary.dispatchers.known)
        assertFalse(header.summary.dispatchers.hasUnknown)
        assertTrue(header.summary.setsDispatcher)
    }

    private fun source(text: String) =
        myFixture.addFileToProject("CallBadges.kt", text.trimIndent()) as KtFile

    private fun offset(file: KtFile, call: String): Int = file.text.lastIndexOf(call) + call.length

    private fun analyze(file: KtFile) = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread<FileAnalysis> {
            ReadAction.compute<FileAnalysis, RuntimeException> { project.service<DispatcherAnalysisService>().analyze(file) }
        },
    )
}
