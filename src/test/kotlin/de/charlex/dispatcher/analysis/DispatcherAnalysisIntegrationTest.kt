package de.charlex.dispatcher.analysis

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import de.charlex.dispatcher.model.Dispatcher
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

class DispatcherAnalysisIntegrationTest : BasePlatformTestCase() {
    override fun getProjectDescriptor() = LightJavaCodeInsightFixtureTestCase.JAVA_21

    override fun setUp() {
        super.setUp()
        System.getProperty("dispatcher.fixture.libraries").split(File.pathSeparator).forEach { path ->
            val library = File(path)
            PsiTestUtil.addLibrary(myFixture.module, library.nameWithoutExtension, library.parent, library.name)
        }
    }

    fun testIncomingContextDiffersFromPureWithContextEffect() {
        val file = source("""
            import kotlinx.coroutines.*
            private suspend fun load() = withContext(Dispatchers.IO) { 42 }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        val result = analyze(file)
        assertEquals(result.toString(), setOf(Dispatcher.Main), declaration(file, result, "load").summary.dispatchers.known)
        val call = call(file, result, "load()", last = true)
        assertEquals(setOf(Dispatcher.IO), call.summary.dispatchers.known)
        assertFalse(call.summary.dispatchers.hasUnknown)
    }

    fun testMixedWorkAndContextRestoration() {
        val file = source("""
            import kotlinx.coroutines.*
            private suspend fun load() { println("before"); withContext(Dispatchers.IO) { println("read") }; println("after") }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        val badge = call(file, analyze(file), "load()", last = true)
        assertEquals(badge.toString(), setOf(Dispatcher.Main, Dispatcher.IO), badge.summary.dispatchers.known)
        assertTrue(badge.summary.badgeSegments().filter { it.dispatcher != null }.all { it.partial })
    }

    fun testTrailingLambdaCallsUseDistinctHeaderAnchors() {
        val file = source("""
            import kotlinx.coroutines.*
            private val worker = Dispatchers.IO
            private suspend fun mixedWorkload() {
                delay(1)
                coroutineScope {
                    coroutineScope<Int> {
                        withContext(worker) { delay(2); 42 }
                    }
                }
                withContext(NonCancellable, block = { delay(3) })
            }
            fun start() { CoroutineScope(Dispatchers.Main).launch { mixedWorkload() } }
        """)
        val result = analyze(file)
        val headers = listOf(
            "delay(1)", "coroutineScope", "coroutineScope<Int>", "withContext(worker)",
            "delay(2)", "delay(3)", "withContext(NonCancellable, block = { delay(3) })", "mixedWorkload()",
        )
        val expected = headers.map { header ->
            val start = if (header == "mixedWorkload()") file.text.lastIndexOf(header) else file.text.indexOf(header)
            start + header.length
        }.toSet()
        assertEquals(expected, result.calls.keys)
        assertEquals(setOf(Dispatcher.IO), call(file, result, "withContext(worker)").summary.dispatchers.known)
        assertEquals(setOf(Dispatcher.Main), call(file, result, "delay(1)").summary.dispatchers.known)
        assertEquals(setOf(Dispatcher.IO), call(file, result, "delay(2)").summary.dispatchers.known)
        assertEquals(setOf(Dispatcher.Main), call(file, result, "delay(3)").summary.dispatchers.known)
    }

    fun testPublicEntryUsesKnownProjectCallers() {
        val file = source("""
            import kotlinx.coroutines.*
            suspend fun load() { println("work") }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        val badge = declaration(file, analyze(file), "load")
        assertEquals(setOf(Dispatcher.Main), badge.summary.dispatchers.known)
        assertFalse(badge.summary.dispatchers.hasUnknown)
    }

    fun testAliasAndNonDispatcherContextPreserveMain() {
        val file = source("""
            import kotlinx.coroutines.*
            import kotlinx.coroutines.withContext as switchContext
            private val ui = Dispatchers.Main.immediate
            private suspend fun load() = switchContext(NonCancellable + CoroutineName("load")) { 42 }
            fun start() { CoroutineScope(ui).launch { load() } }
        """)
        assertEquals(setOf(Dispatcher.Main), call(file, analyze(file), "load()", last = true).summary.dispatchers.known)
    }

    fun testUnknownInjectedDispatcherRemainsUncertain() {
        val file = source("""
            import kotlinx.coroutines.*
            suspend fun load(dispatcher: CoroutineDispatcher) = withContext(dispatcher) { 42 }
            fun start(dispatcher: CoroutineDispatcher) { CoroutineScope(Dispatchers.Main).launch { load(dispatcher) } }
        """)
        assertTrue(call(file, analyze(file), "load(dispatcher)", last = true).summary.dispatchers.hasUnknown)
    }

    fun testShadowedWithContextDoesNotSelectIO() {
        val file = source("""
            import kotlinx.coroutines.*
            private suspend fun withContext(ignored: CoroutineDispatcher, block: suspend () -> Int) = block()
            private suspend fun load() = withContext(Dispatchers.IO) { 42 }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        val summary = call(file, analyze(file), "load()", last = true).summary
        assertFalse(Dispatcher.IO in summary.dispatchers.known)
        assertTrue(summary.dispatchers.hasUnknown)
    }

    fun testRecursiveGraphTerminatesAndRetainsUncertainty() {
        val file = source("""
            import kotlinx.coroutines.*
            private suspend fun first() { second() }
            private suspend fun second() { first() }
            fun start() { CoroutineScope(Dispatchers.Main).launch { first() } }
        """)
        val result = analyze(file)
        assertTrue(result.declarations.isNotEmpty())
        assertTrue(call(file, result, "first()", last = true).summary.dispatchers.hasUnknown)
    }

    fun testSuspendExpectFunctionsAreExcluded() {
        val file = source("""
            import kotlinx.coroutines.*
            expect suspend fun platformLoad(): Int
            fun start() { CoroutineScope(Dispatchers.Main).launch { platformLoad() } }
        """)
        val result = analyze(file)
        assertTrue(result.declarations.isEmpty())
        assertFalse(result.calls.containsKey(file.text.lastIndexOf("platformLoad()") + "platformLoad()".length))
    }

    fun testFinallyRunsInTheRestoredContext() {
        val file = source("""
            import kotlinx.coroutines.*
            private suspend fun load(): Int = try { withContext(Dispatchers.IO) { 42 } } finally { println("cleanup") }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        assertEquals(setOf(Dispatcher.Main, Dispatcher.IO), call(file, analyze(file), "load()", true).summary.dispatchers.known)
    }

    fun testAsyncChildDoesNotChangeParentWorkload() {
        val file = source("""
            import kotlinx.coroutines.*
            private suspend fun load() = coroutineScope { launch(Dispatchers.IO) { println("child") }; println("parent") }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        assertEquals(setOf(Dispatcher.Main), call(file, analyze(file), "load()", true).summary.dispatchers.known)
    }

    fun testEscapingCallableReferenceAddsUnknown() {
        val file = source("""
            import kotlinx.coroutines.*
            private suspend fun load() = 42
            val callback = ::load
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        assertTrue(declaration(file, analyze(file), "load").summary.dispatchers.hasUnknown)
    }

    fun testExecutorBackedCustomDispatcherKeepsIdentity() {
        val file = source("""
            import kotlinx.coroutines.*
            import java.util.concurrent.Executors
            private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            private suspend fun load() = withContext(worker) { 42 }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        val summary = call(file, analyze(file), "load()", true).summary
        assertEquals(1, summary.dispatchers.known.size)
        assertTrue(summary.dispatchers.known.single() is Dispatcher.Custom)
        assertFalse(summary.dispatchers.hasUnknown)
    }

    fun testCrossFileCalleeEditInvalidatesCaller() {
        val callee = myFixture.addFileToProject("Library.kt", """
            import kotlinx.coroutines.*
            internal suspend fun load() = withContext(Dispatchers.IO) { 42 }
        """.trimIndent()) as KtFile
        val caller = source("""
            import kotlinx.coroutines.*
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        assertEquals(setOf(Dispatcher.IO), call(caller, analyze(caller), "load()", true).summary.dispatchers.known)
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(callee)!!
            document.setText(document.text.replace("Dispatchers.IO", "Dispatchers.Default"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        assertEquals(setOf(Dispatcher.Default), call(caller, analyze(caller), "load()", true).summary.dispatchers.known)
    }

    fun testLargeFixtureReusesImmutableSnapshot() {
        val functions = (0 until 100).joinToString("\n") { "private suspend fun load$it() = withContext(Dispatchers.IO) { $it }" }
        val calls = (0 until 100).joinToString("; ") { "load$it()" }
        val file = source("import kotlinx.coroutines.*\n$functions\nfun start() { CoroutineScope(Dispatchers.Main).launch { $calls } }")
        val coldStart = System.nanoTime()
        val cold = analyze(file)
        val coldMs = (System.nanoTime() - coldStart) / 1_000_000
        val warmStart = System.nanoTime()
        val warm = analyze(file)
        val warmMs = (System.nanoTime() - warmStart) / 1_000_000
        val heapMiB = java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / (1024 * 1024)
        println("Dispatcher benchmark: 100 functions, 200 suspend calls; cold=${coldMs}ms warm=${warmMs}ms heap=${heapMiB}MiB")
        assertEquals(100, cold.declarations.size)
        assertSame(cold, warm)
        assertTrue("Cold analysis regression: ${coldMs}ms", coldMs < 30_000)
        assertTrue("Cached analysis regression: ${warmMs}ms", warmMs < 1_000)
    }

    fun testProjectBeyondFormerFunctionLimitIsAnalyzed() {
        val functions = (0 until 1050).joinToString("\n") { "private suspend fun load$it() = $it" }
        val calls = (0 until 1050).joinToString("; ") { "load$it()" }
        val file = source("import kotlinx.coroutines.*\n$functions\nfun start() { CoroutineScope(Dispatchers.Main).launch { $calls } }")
        val result = analyze(file)
        assertEquals(1050, result.declarations.size)
        assertTrue(result.declarations.values.all { it.summary.dispatchers.known == setOf(Dispatcher.Main) })
        assertTrue(result.declarations.values.none { it.summary.dispatchers.hasUnknown })
    }

    fun testDeepCallGraphConvergesBeyondFormerRoundLimit() {
        val functions = (0 until 70).joinToString("\n") { "private suspend fun load$it(): Int = load${it + 1}()" }
        val file = source("""
            import kotlinx.coroutines.*
            $functions
            private suspend fun load70(): Int = withContext(Dispatchers.IO) { 42 }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load0() } }
        """)
        val badge = call(file, analyze(file), "load0()", true)
        assertEquals(setOf(Dispatcher.IO), badge.summary.dispatchers.known)
        assertFalse(badge.summary.dispatchers.hasUnknown)
    }

    fun testCrossFileGraphBeyondFormerFileLimitIsAnalyzed() {
        (0 until 130).forEach { index ->
            myFixture.addFileToProject("Source$index.kt", "import kotlinx.coroutines.*\ninternal suspend fun work$index() = withContext(Dispatchers.IO) { $index }")
        }
        val calls = (0 until 130).joinToString("; ") { "work$it()" }
        val file = source("import kotlinx.coroutines.*\nfun start() { CoroutineScope(Dispatchers.Main).launch { $calls } }")
        val result = analyze(file)
        assertEquals(130, result.calls.size)
        assertTrue(result.calls.values.all { it.summary.dispatchers.known == setOf(Dispatcher.IO) && !it.summary.dispatchers.hasUnknown })
    }

    private fun source(code: String): KtFile = myFixture.addFileToProject("Fixture.kt", code.trimIndent()) as KtFile

    private fun analyze(file: KtFile): FileAnalysis = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread<FileAnalysis> {
            ReadAction.compute<FileAnalysis, RuntimeException> {
                project.service<DispatcherAnalysisService>().analyze(file)
            }
        },
    )

    private fun declaration(file: KtFile, result: FileAnalysis, name: String): BadgeResult {
        val offset = file.text.indexOf("suspend fun $name")
        return result.declarations.entries.firstOrNull { (start, _) ->
            start <= offset && offset - start < 10
        }?.value ?: error("Missing declaration $name: ${result.declarations}")
    }

    private fun call(file: KtFile, result: FileAnalysis, text: String, last: Boolean = false): BadgeResult {
        val offset = (if (last) file.text.lastIndexOf(text) else file.text.indexOf(text)) + text.length
        return result.calls[offset] ?: error("Missing call at $offset: ${result.calls}")
    }
}
