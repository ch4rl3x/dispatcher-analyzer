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

class DeclarationEvidenceTest : BasePlatformTestCase() {
    override fun getProjectDescriptor() = LightJavaCodeInsightFixtureTestCase.JAVA_21

    override fun setUp() {
        super.setUp()
        System.getProperty("dispatcher.fixture.libraries").split(File.pathSeparator).forEach { path ->
            val library = File(path)
            PsiTestUtil.addLibrary(module, library.nameWithoutExtension, library.parent, library.name)
        }
    }

    fun testUnusedPublicAndPrivateFunctionsIgnoreDocumentationReferences() {
        val file = source("""
            suspend fun externallyCallableWork() = 42
            private suspend fun privateWork() = 42
        """)
        myFixture.addFileToProject("README.md", "Call externallyCallableWork() or privateWork().")
        assertTrue(request(file).declarations.isEmpty())
        assertTrue(analyze(file).declarations.isEmpty())
        assertTrue(request(file).declarations.isEmpty())
    }

    fun testUnusedFunctionRetainsUnknownCallSiteEffects() {
        val file = source("""
            import kotlinx.coroutines.delay
            suspend fun externallyCallableWork() { delay(1) }
        """)
        val result = analyze(file)
        assertTrue(result.declarations.isEmpty())
        val offset = file.text.indexOf("delay(1)") + "delay(1)".length
        val dispatchers = result.calls.getValue(offset).summary.dispatchers
        assertTrue(dispatchers.hasUnknown)
        assertTrue(dispatchers.known.isEmpty())
    }

    fun testPublicCallChainUsesOnlyKnownProjectCallers() {
        val file = source("""
            import kotlinx.coroutines.*
            public suspend fun externallyCallableWork() { nestedWork() }
            internal suspend fun nestedWork() { delay(1) }
        """)
        val caller = myFixture.addFileToProject("Caller.kt", """
            import kotlinx.coroutines.*
            fun launchExamples(scope: CoroutineScope) {
                scope.launch(Dispatchers.Main) { externallyCallableWork() }
            }
        """.trimIndent()) as KtFile
        myFixture.addFileToProject("README.md", "Call externallyCallableWork().")

        val main = analyze(file)
        assertEquals(2, main.declarations.size)
        (main.declarations.values + main.calls.values).forEach { badge ->
            assertEquals(setOf(Dispatcher.Main), badge.summary.dispatchers.known)
            assertFalse(badge.summary.dispatchers.hasUnknown)
        }

        replace(caller, """
            import kotlinx.coroutines.*
            fun launchExamples(scope: CoroutineScope) {
                scope.launch(Dispatchers.Main) { externallyCallableWork() }
                scope.launch(Dispatchers.IO) { externallyCallableWork() }
            }
        """.trimIndent())
        analyze(file).declarations.values.forEach { badge ->
            assertEquals(setOf(Dispatcher.Main, Dispatcher.IO), badge.summary.dispatchers.known)
            assertFalse(badge.summary.dispatchers.hasUnknown)
        }

        replace(caller, """
            import kotlinx.coroutines.*
            fun launchExamples(scope: CoroutineScope) {
                scope.launch(Dispatchers.IO) { externallyCallableWork() }
            }
        """.trimIndent())
        analyze(file).declarations.values.forEach { badge ->
            assertEquals(setOf(Dispatcher.IO), badge.summary.dispatchers.known)
            assertFalse(badge.summary.dispatchers.hasUnknown)
        }
    }

    fun testPublicFunctionKeepsUncertaintyFromUnresolvedProjectEntry() {
        val file = source("""
            import kotlinx.coroutines.*
            public suspend fun work() { delay(1) }
            suspend fun unknownEntry() { work() }
            fun mainEntry(scope: CoroutineScope) {
                scope.launch(Dispatchers.Main) { work() }
            }
        """)
        val badge = analyze(file).declarations.values.single()
        assertEquals(setOf(Dispatcher.Main), badge.summary.dispatchers.known)
        assertTrue(badge.summary.dispatchers.hasUnknown)
    }

    fun testPublicFunctionKeepsUncertaintyFromEscapingReference() {
        val file = source("""
            import kotlinx.coroutines.*
            public suspend fun work() { delay(1) }
            val callback: suspend () -> Unit = ::work
            fun mainEntry(scope: CoroutineScope) {
                scope.launch(Dispatchers.Main) { work() }
            }
        """)
        val badge = analyze(file).declarations.values.single()
        assertEquals(setOf(Dispatcher.Main), badge.summary.dispatchers.known)
        assertTrue(badge.summary.dispatchers.hasUnknown)
    }

    fun testCallableReferenceAloneDoesNotCreateDeclarationBadge() {
        val file = source("""
            private suspend fun load() = 42
            val callback: suspend () -> Int = ::load
        """)
        assertTrue(analyze(file).declarations.isEmpty())
    }

    fun testActualCallWithUnknownContextCreatesDeclarationBadge() {
        val file = source("""
            private suspend fun load() = 42
            suspend fun entry() = load()
        """)
        val result = analyze(file)
        val offset = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
            .single { it.name == "load" }.textRange.startOffset
        assertEquals(setOf(offset), result.declarations.keys)
        assertTrue(result.declarations.getValue(offset).summary.dispatchers.hasUnknown)
        assertTrue(result.declarations.getValue(offset).summary.dispatchers.known.isEmpty())
    }

    fun testUnresolvedCodeCallKeepsPotentialDeclarationUncertain() {
        val file = source("""
            private suspend fun load(value: Int) = value
            suspend fun entry() = load(missingArgument)
        """)
        val result = analyze(file)
        assertEquals(1, result.declarations.size)
        assertTrue(result.declarations.values.single().summary.dispatchers.hasUnknown)
    }

    fun testUnchangedFileRetainsOnlyEstablishedGrayEvidenceUntilRefresh() {
        val file = source("suspend fun load() = 42")
        val caller = myFixture.addFileToProject("Caller.kt", "suspend fun entry() = load()") as KtFile
        assertEquals(1, analyze(file).declarations.size)
        replace(caller, "suspend fun entry() = 42")

        val pending = request(file)
        assertEquals(1, pending.declarations.size)
        val badge = pending.declarations.values.single()
        assertTrue(badge.summary.dispatchers.hasUnknown)
        assertTrue(badge.summary.dispatchers.known.isEmpty())
        assertTrue(badge.tooltip.isNotBlank())
        assertTrue(analyze(file).declarations.isEmpty())
    }

    fun testEditedFileOmitsPreviousPositionsUntilCallEvidenceIsCurrent() {
        val file = source("private suspend fun load() = 42\nsuspend fun entry() = load()")
        assertEquals(1, analyze(file).declarations.size)
        replace(file, "private suspend fun load() = 42")
        val pending = request(file)
        assertTrue(pending.declarations.isEmpty())
        assertTrue(pending.calls.isEmpty())
        assertTrue(analyze(file).declarations.isEmpty())
    }

    fun testRecreatedFileAtSameUrlCannotReusePreviousGrayPositions() {
        val original = source("private suspend fun load() = 42\nsuspend fun entry() = load()")
        val originalUrl = original.virtualFile.url
        val previous = analyze(original)
        assertEquals(1, previous.declarations.size)
        assertFalse(previous.calls.isEmpty())
        WriteCommandAction.runWriteCommandAction(project) { original.delete() }
        val replacement = source("private suspend fun replacement() = 42")
        assertEquals(originalUrl, replacement.virtualFile.url)
        val pending = request(replacement)
        assertTrue(pending.declarations.isEmpty())
        assertTrue(pending.calls.isEmpty())
        assertTrue(analyze(replacement).declarations.isEmpty())
    }

    private fun source(text: String) = myFixture.addFileToProject("Evidence.kt", text.trimIndent()) as KtFile

    private fun replace(file: KtFile, text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            PsiDocumentManager.getInstance(project).getDocument(file)!!.setText(text)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }

    private fun request(file: KtFile) = ReadAction.compute<FileAnalysis, RuntimeException> {
        project.service<DispatcherAnalysisService>().requestAnalysis(file)
    }

    private fun analyze(file: KtFile) = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread<FileAnalysis> {
            ReadAction.compute<FileAnalysis, RuntimeException> {
                project.service<DispatcherAnalysisService>().analyze(file)
            }
        },
    )
}
