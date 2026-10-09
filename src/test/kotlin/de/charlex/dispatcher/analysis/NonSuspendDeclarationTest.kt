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
import de.charlex.dispatcher.model.Dispatcher
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.io.File

class NonSuspendDeclarationTest : BasePlatformTestCase() {
    override fun getProjectDescriptor() = LightJavaCodeInsightFixtureTestCase.JAVA_21

    override fun setUp() {
        super.setUp()
        System.getProperty("dispatcher.fixture.libraries").split(File.pathSeparator).forEach { path ->
            val library = File(path)
            PsiTestUtil.addLibrary(module, library.nameWithoutExtension, library.parent, library.name)
        }
    }

    fun testIncomingContextsAndOriginsPropagateThroughOrdinaryHelpers() {
        val file = source("""
            fun parse() = 42
            fun bridge() = parse()
            suspend fun load() {
                bridge()
                withContext(Dispatchers.IO) { bridge() }
            }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        val result = analyze(file)
        for (name in listOf("parse", "bridge")) {
            val incoming = declaration(file, result, name).summary.dispatchers
            assertEquals(setOf(Dispatcher.Main, Dispatcher.IO), incoming.known)
            assertFalse(incoming.hasUnknown)
            for ((dispatcher, label) in mapOf(Dispatcher.Main to "Main", Dispatcher.IO to "IO")) {
                assertEquals(setOf(file.text.indexOf("Dispatchers.$label")),
                    incoming.origins.getValue(dispatcher).map { it.offset }.toSet())
            }
        }
        assertEquals(setOf(offset(file, "parse"), offset(file, "bridge")), result.nonSuspendDeclarations.keys)
        assertFalse("Ordinary calls do not acquire call-site badges",
            file.text.lastIndexOf("bridge()") + "bridge()".length in result.calls)
    }

    fun testRecursiveHelpersReachFixedPoint() {
        val file = source("""
            fun first(n: Int): Int = if (n > 0) second(n - 1) else 0
            fun second(n: Int): Int = first(n)
            fun start() { CoroutineScope(Dispatchers.Default).launch { first(2) } }
        """)
        val result = analyze(file)
        for (name in listOf("first", "second")) {
            val incoming = declaration(file, result, name).summary.dispatchers
            assertEquals(setOf(Dispatcher.Default), incoming.known)
            assertFalse(incoming.hasUnknown)
        }
    }

    fun testUnknownEntriesCallbacksAndEscapingReferencesRemainUncertain() {
        val file = source("""
            fun parse() = 42
            fun unknownEntry() = parse()
            fun start() { CoroutineScope(Dispatchers.Main).launch { parse() } }
            val callback = { parse() }
            val reference = ::parse
            fun unused() = 1
            val unusedReference = ::unused
        """)
        val result = analyze(file)
        val incoming = declaration(file, result, "parse").summary.dispatchers
        assertEquals(setOf(Dispatcher.Main), incoming.known)
        assertTrue(incoming.hasUnknown)
        assertTrue(incoming.unknownReasons.any { "No proven callers" in it })
        assertTrue(incoming.unknownReasons.any { "lambda" in it })
        assertTrue(incoming.unknownReasons.any { "reference" in it })
        assertEquals(setOf(offset(file, "parse")), result.nonSuspendDeclarations.keys)
    }

    fun testAsyncChildrenAndBlockingBodiesDoNotReplaceHelperIncomingContext() {
        val file = source("""
            fun child() = 42
            fun helper() {
                CoroutineScope(Dispatchers.IO).launch { child() }
                runBlocking(Dispatchers.Default) { child() }
            }
            fun start() { CoroutineScope(Dispatchers.Main).launch { helper() } }
        """)
        val result = analyze(file)
        val parent = declaration(file, result, "helper").summary.dispatchers
        assertEquals(setOf(Dispatcher.Main), parent.known)
        assertFalse(parent.hasUnknown)
        val child = declaration(file, result, "child").summary.dispatchers
        assertEquals(setOf(Dispatcher.IO, Dispatcher.Default), child.known)
        assertFalse(child.hasUnknown)
    }

    fun testDeferredAnonymousFunctionsAndVirtualTargetsStayUncertain() {
        val file = source("""
            fun parse() = 42
            open class Worker { open fun work() = parse() }
            val callback = fun() { parse() }
            fun start(worker: Worker) { CoroutineScope(Dispatchers.IO).launch { worker.work() } }
        """)
        val result = analyze(file)
        assertTrue(declaration(file, result, "work").summary.dispatchers.hasUnknown)
        val parse = declaration(file, result, "parse").summary.dispatchers
        assertEquals(setOf(Dispatcher.IO), parse.known)
        assertTrue(parse.hasUnknown)
        assertEquals(setOf(offset(file, "parse"), offset(file, "work")), result.nonSuspendDeclarations.keys)
    }

    fun testExpectFunctionsAreExcludedAndOverloadsUseResolvedIdentity() {
        val file = source("""
            expect fun platformWork(): Int
            fun parse(value: Int) = value
            fun parse(value: String) = value
            fun start() { CoroutineScope(Dispatchers.IO).launch { parse(1); platformWork() } }
        """)
        val result = analyze(file)
        assertEquals(setOf(file.text.indexOf("fun parse(value: Int)")), result.nonSuspendDeclarations.keys)
        assertEquals(setOf(Dispatcher.IO), result.nonSuspendDeclarations.values.single().summary.dispatchers.known)
    }

    fun testInvalidatedUnchangedHelpersLoseOriginsAndRemovedCallsLoseBadges() {
        val helper = source("fun parse() = 42")
        val caller = myFixture.addFileToProject("Caller.kt", """
            import kotlinx.coroutines.*
            fun start() { CoroutineScope(Dispatchers.IO).launch { parse() } }
        """.trimIndent()) as KtFile
        val initial = analyze(helper)
        assertEquals(setOf(Dispatcher.IO), declaration(helper, initial, "parse").summary.dispatchers.known)
        WriteCommandAction.runWriteCommandAction(project) {
            PsiDocumentManager.getInstance(project).getDocument(caller)!!.setText(caller.text.replace("parse()", "println(1)"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val pending = ReadAction.compute<FileAnalysis, RuntimeException> {
            project.service<DispatcherAnalysisService>().requestAnalysis(helper)
        }
        val stale = declaration(helper, pending, "parse").summary.dispatchers
        assertTrue(stale.hasUnknown)
        assertTrue(stale.known.isEmpty())
        assertTrue(stale.origins.isEmpty())
        assertTrue(analyze(helper).nonSuspendDeclarations.isEmpty())
    }

    private fun source(code: String) = myFixture.addFileToProject(
        "Helpers.kt", "import kotlinx.coroutines.*\n" + code.trimIndent(),
    ) as KtFile

    private fun offset(file: KtFile, name: String) =
        PsiTreeUtil.collectElementsOfType(file, KtNamedFunction::class.java).single { it.name == name }.textRange.startOffset

    private fun declaration(file: KtFile, result: FileAnalysis, name: String) =
        result.nonSuspendDeclarations.getValue(offset(file, name))

    private fun analyze(file: KtFile) = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread<FileAnalysis> {
            ReadAction.compute<FileAnalysis, RuntimeException> {
                project.service<DispatcherAnalysisService>().analyze(file)
            }
        },
    )
}
