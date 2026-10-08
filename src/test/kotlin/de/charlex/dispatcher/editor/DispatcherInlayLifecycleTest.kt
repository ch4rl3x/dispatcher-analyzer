package de.charlex.dispatcher.editor

import com.intellij.codeInsight.hints.InlayHintsSwitch
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import de.charlex.dispatcher.analysis.DispatcherAnalysisListener
import de.charlex.dispatcher.analysis.DispatcherAnalysisService
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Assert.assertNotEquals
import java.io.File
import java.awt.datatransfer.DataFlavor

class DispatcherInlayLifecycleTest : BasePlatformTestCase() {
    override fun getProjectDescriptor() = LightJavaCodeInsightFixtureTestCase.JAVA_21

    fun testCallSiteModeChangesRefreshUnchangedEditor() {
        System.getProperty("dispatcher.fixture.libraries").split(File.pathSeparator).forEach { path ->
            val library = File(path)
            PsiTestUtil.addLibrary(myFixture.module, library.nameWithoutExtension, library.parent, library.name)
        }
        val settings = service<DispatcherSettings>()
        val saved = settings.getState()
        try {
            settings.loadState(DispatcherHintSettings())
            val source = """
                import kotlinx.coroutines.Dispatchers
                import kotlinx.coroutines.withContext

                private suspend fun load( ){}
                suspend fun refresh( ){
                    load( )
                    withContext( Dispatchers.IO ){load( )}
                }
            """.trimIndent()
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
            assertTrue("Called suspend declarations should have block hints", blocks >= 1)

            val switches = ExtensionPointName.create<InlayHintsSwitch>("com.intellij.codeInsight.inlayHintsSwitch")
                .extensionList
            val preferences = switches.map { it.isEnabled(project) }
            settings.updateCalls(CallSiteBadgeMode.ALL)
            myFixture.doHighlighting()
            assertEquals(preferences, switches.map { it.isEnabled(project) })
            val allCalls = editor.inlayModel.getInlineElementsInRange(0, length).size
            assertTrue("ALL should show every call site", allCalls > inline)
            assertEquals(blocks, editor.inlayModel.getBlockElementsInRange(0, length).size)

            settings.updateCalls(CallSiteBadgeMode.DISPATCHER_CHANGES)
            myFixture.doHighlighting()
            val dispatcherChanges = editor.inlayModel.getInlineElementsInRange(0, length).size
            assertTrue("Dispatcher changes should be a filtered subset", dispatcherChanges in 1 until allCalls)
            assertEquals(blocks, editor.inlayModel.getBlockElementsInRange(0, length).size)
            assertEquals(source, editor.document.text)

            editor.selectionModel.setSelection(0, length)
            myFixture.performEditorAction(IdeActions.ACTION_EDITOR_COPY)
            assertEquals(source, CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))
            editor.selectionModel.removeSelection()
            val fontSize = editor.colorsScheme.editorFontSize
            try {
                editor.colorsScheme.editorFontSize = fontSize + 4
                myFixture.doHighlighting()
                assertEquals(source, editor.document.text)
                assertEquals(blocks, editor.inlayModel.getBlockElementsInRange(0, length).size)
            } finally {
                editor.colorsScheme.editorFontSize = fontSize
            }

            val beforeReformat = editor.document.text
            WriteCommandAction.runWriteCommandAction(project) {
                CodeStyleManager.getInstance(project).reformat(file)
            }
            assertNotEquals(beforeReformat, editor.document.text)
            assertFalse(editor.document.text.contains("called within Dispatcher "))
            myFixture.performEditorAction(IdeActions.ACTION_UNDO)
            assertEquals(source, editor.document.text)

            settings.updateCalls(CallSiteBadgeMode.NONE)
            myFixture.doHighlighting()
            assertEquals(inline, editor.inlayModel.getInlineElementsInRange(0, length).size)
            assertEquals(source, editor.document.text)
        } finally {
            settings.loadState(saved)
        }
    }
}
