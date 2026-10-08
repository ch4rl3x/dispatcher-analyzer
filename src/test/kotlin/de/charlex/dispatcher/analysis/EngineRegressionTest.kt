package de.charlex.dispatcher.analysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import de.charlex.dispatcher.model.Dispatcher
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.io.File

class EngineRegressionTest : BasePlatformTestCase() {
    override fun getProjectDescriptor() = LightJavaCodeInsightFixtureTestCase.JAVA_21

    override fun setUp() {
        super.setUp()
        System.getProperty("dispatcher.fixture.libraries").split(File.pathSeparator).forEach { path ->
            val library = File(path)
            PsiTestUtil.addLibrary(myFixture.module, library.nameWithoutExtension, library.parent, library.name)
        }
    }

    fun testSourceHelperCannotHideSynchronousDispatcherSwitch() {
        val file = source("""
            fun bridge() = runBlocking(Dispatchers.IO) { println("io") }
            private suspend fun load() { bridge() }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        val summary = lastCall(file, "load()").summary
        assertTrue(Dispatcher.Main in summary.dispatchers.known)
        assertTrue(summary.dispatchers.hasUnknown)
    }

    fun testUnsupportedInlineCallbackCannotClaimCompleteCallerCoverage() {
        val file = source("""
            inline fun invokeNow(block: () -> Unit) = block()
            private suspend fun load() { invokeNow { withContext(Dispatchers.IO) { println("io") } } }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        assertTrue(lastCall(file, "load()").summary.dispatchers.hasUnknown)
    }

    fun testGetterCallbackAddsUnknownIncomingPath() {
        val file = source("""
            private suspend fun load() { println("work") }
            val callback: suspend () -> Unit get() = { load() }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        val summary = declaration(file, "load").summary
        assertTrue(Dispatcher.Main in summary.dispatchers.known)
        assertTrue(summary.dispatchers.hasUnknown)
    }

    fun testDefaultCallbackAddsUnknownIncomingPath() {
        val file = source("""
            private suspend fun load() { println("work") }
            fun configure(callback: suspend () -> Unit = { load() }) {}
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        val summary = declaration(file, "load").summary
        assertTrue(Dispatcher.Main in summary.dispatchers.known)
        assertTrue(summary.dispatchers.hasUnknown)
    }

    fun testOverridableDispatcherAliasRemainsUnknown() {
        val file = source("""
            open class Base { open val dispatcher: CoroutineDispatcher = Dispatchers.Main }
            class Child : Base() { override val dispatcher: CoroutineDispatcher = Dispatchers.IO }
            private suspend fun load() { println("work") }
            fun start(base: Base) { CoroutineScope(base.dispatcher).launch { load() } }
        """)
        val summary = declaration(file, "load").summary
        assertTrue(summary.dispatchers.hasUnknown)
        assertFalse(Dispatcher.Main in summary.dispatchers.known)
    }

    fun testOverridableScopeAliasRemainsUnknown() {
        val file = source("""
            open class Base { open val scope: CoroutineScope = MainScope() }
            class Child : Base() { override val scope = CoroutineScope(Dispatchers.IO) }
            private suspend fun load() { println("work") }
            fun start(base: Base) { base.scope.launch { load() } }
        """)
        val summary = declaration(file, "load").summary
        assertTrue(summary.dispatchers.hasUnknown)
        assertFalse(Dispatcher.Main in summary.dispatchers.known)
    }

    fun testConstructorCannotHideSynchronousDispatcherSwitch() {
        val file = source("""
            class Bridge { init { runBlocking(Dispatchers.IO) { println("io") } } }
            private suspend fun load() { Bridge() }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        assertTrue(lastCall(file, "load()").summary.dispatchers.hasUnknown)
    }

    fun testMissingComputedDefaultPreventsCompleteCalleeCoverage() {
        val file = source("""
            fun argument(): Int { println("argument"); return 1 }
            private suspend fun load(value: Int = argument()) = withContext(Dispatchers.IO) { println(value) }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        val summary = lastCall(file, "load()").summary
        assertTrue(Dispatcher.IO in summary.dispatchers.known)
        assertTrue(summary.dispatchers.hasUnknown)
    }

    fun testComputedPropertyCannotHideSynchronousDispatcherSwitch() {
        val file = source("""
            val value: Int get() = runBlocking(Dispatchers.IO) { 42 }
            private suspend fun load() { println(value) }
            fun start() { CoroutineScope(Dispatchers.Main).launch { load() } }
        """)
        assertTrue(lastCall(file, "load()").summary.dispatchers.hasUnknown)
    }

    private fun source(code: String) = myFixture.addFileToProject(
        "Regression.kt", "import kotlinx.coroutines.*\n" + code.trimIndent(),
    ) as KtFile

    private fun analyze(file: KtFile): FileAnalysis = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread<FileAnalysis> {
            ReadAction.compute<FileAnalysis, RuntimeException> {
                project.service<DispatcherAnalysisService>().analyze(file)
            }
        },
    )

    private fun lastCall(file: KtFile, text: String): BadgeResult =
        analyze(file).calls.getValue(file.text.lastIndexOf(text) + text.length)

    private fun declaration(file: KtFile, name: String): BadgeResult {
        val declaration = PsiTreeUtil.collectElementsOfType(file, KtNamedFunction::class.java).single { it.name == name }
        return analyze(file).declarations.getValue(declaration.textRange.startOffset)
    }
}
