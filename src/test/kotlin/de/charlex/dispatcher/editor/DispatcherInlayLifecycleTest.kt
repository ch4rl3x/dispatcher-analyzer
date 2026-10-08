package de.charlex.dispatcher.editor

import com.intellij.codeInsight.hints.InlayHintsSwitch
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import de.charlex.dispatcher.analysis.DispatcherAnalysisListener
import de.charlex.dispatcher.analysis.DispatcherAnalysisService
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

class DispatcherInlayLifecycleTest : BasePlatformTestCase() {
    override fun getProjectDescriptor() = LightJavaCodeInsightFixtureTestCase.JAVA_21

    fun testCallToggleRefreshesUnchangedEditor() {
        System.getProperty("dispatcher.fixture.libraries").split(File.pathSeparator).forEach { path ->
            val library = File(path)
            PsiTestUtil.addLibrary(myFixture.module, library.nameWithoutExtension, library.parent, library.name)
        }
        val settings = service<DispatcherSettings>()
        val saved = settings.getState()
        try {
            settings.loadState(DispatcherHintSettings())
            val source = "private suspend fun load() {}\nsuspend fun refresh() { load() }"
            val file = myFixture.configureByText("LiveBadges.kt", source) as KtFile
            var completed = false
            project.messageBus.connect(testRootDisposable).subscribe(
                DispatcherAnalysisListener.TOPIC,
                object : DispatcherAnalysisListener {
                    override fun analysisUpdated() {
                        assertTrue(ApplicationManager.getApplication().isDispatchThread)
                        completed = true
                    }
                },
            )
            myFixture.doHighlighting()
            PlatformTestUtil.waitWithEventsDispatching(
                "Dispatcher analysis should refresh hints after the typing pause",
                { completed && project.service<DispatcherAnalysisService>().hasCurrentAnalysis(file) },
                30,
            )
            myFixture.doHighlighting()
            val editor = myFixture.editor
            val length = editor.document.textLength
            val blocks = editor.inlayModel.getBlockElementsInRange(0, length).size
            val inline = editor.inlayModel.getInlineElementsInRange(0, length).size
            assertTrue("Suspend declarations should have block hints", blocks >= 2)

            val switches = ExtensionPointName.create<InlayHintsSwitch>("com.intellij.codeInsight.inlayHintsSwitch")
                .extensionList
            val preferences = switches.map { it.isEnabled(project) }
            settings.updateCalls(true)
            myFixture.doHighlighting()
            assertEquals(preferences, switches.map { it.isEnabled(project) })
            assertEquals(inline + 1, editor.inlayModel.getInlineElementsInRange(0, length).size)
            assertEquals(blocks, editor.inlayModel.getBlockElementsInRange(0, length).size)

            settings.updateCalls(false)
            myFixture.doHighlighting()
            assertEquals(inline, editor.inlayModel.getInlineElementsInRange(0, length).size)
            assertEquals(source, editor.document.text)
        } finally {
            settings.loadState(saved)
        }
    }
}
