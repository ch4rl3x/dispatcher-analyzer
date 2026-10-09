package de.charlex.dispatcher.editor

import com.intellij.codeInsight.hints.InlayHintsSwitch
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.BlockInlayPriority
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import de.charlex.dispatcher.analysis.DispatcherAnalysisListener
import de.charlex.dispatcher.analysis.DispatcherAnalysisService
import de.charlex.dispatcher.model.Dispatcher
import de.charlex.dispatcher.model.EffectSummary
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Assert.assertNotEquals
import java.io.File
import java.awt.datatransfer.DataFlavor

class DispatcherInlayLifecycleTest : BasePlatformTestCase() {
    override fun getProjectDescriptor() = LightJavaCodeInsightFixtureTestCase.JAVA_21

    fun testExplicitDispatcherCallsStayHiddenAfterSettingsAndSavedEdits() {
        System.getProperty("dispatcher.fixture.libraries").split(File.pathSeparator).forEach { path ->
            val library = File(path)
            PsiTestUtil.addLibrary(myFixture.module, library.nameWithoutExtension, library.parent, library.name)
        }
        val settings = service<DispatcherSettings>()
        val saved = settings.getState()
        try {
            settings.loadState(
                DispatcherHintSettings(callSiteMode = CallSiteBadgeMode.ALL, onlyInFunctionContainingCaret = true),
            )
            val source = """
                import kotlinx.coroutines.CoroutineScope
                import kotlinx.coroutines.launch
                import kotlinx.coroutines.Dispatchers
                import kotlinx.coroutines.delay
                import kotlinx.coroutines.withContext

                private suspend fun readFromDisk() = delay(2)
                private suspend fun mixedWorkload() {
                    delay(1)
                    withContext(Dispatchers.IO) {
                        readFromDisk()
                    }
                }
                private suspend fun entry() = mixedWorkload()
                fun start() { CoroutineScope(Dispatchers.Main).launch { entry() } }
            """.trimIndent()
            val file = myFixture.addFileToProject("TrailingLambdaBadges.kt", source) as KtFile
            myFixture.configureFromExistingVirtualFile(file.virtualFile)
            val editor = myFixture.editor
            editor.caretModel.moveToOffset(source.indexOf("delay(1)"))
            FileDocumentManager.getInstance().saveAllDocuments()
            val analysis = project.service<DispatcherAnalysisService>()
            analysis.startAnalysis()
            PlatformTestUtil.waitWithEventsDispatching(
                "Initial trailing-lambda analysis",
                { analysis.hasCurrentAnalysis(file) },
                30,
            )

            fun headerOffset(text: String, dispatcher: String): Int {
                val header = "withContext(Dispatchers.$dispatcher)"
                return text.indexOf(header) + header.length
            }
            fun lambdaEndOffset(text: String) = text.indexOf('}', text.indexOf("withContext(")) + 1
            val header = headerOffset(source, "IO")
            val lambdaEnd = lambdaEndOffset(source)
            val delayCall = source.indexOf("delay(1)") + "delay(1)".length
            val readCall = source.lastIndexOf("readFromDisk()") + "readFromDisk()".length
            val caller = source.lastIndexOf("mixedWorkload()") + "mixedWorkload()".length
            myFixture.doHighlighting()
            awaitInlineOffsets(editor, source.length) {
                header !in it && delayCall in it && readCall in it && lambdaEnd !in it && caller !in it
            }
            val declarationCount = editor.inlayModel.getBlockElementsInRange(0, source.length).size
            assertTrue("Called suspend functions retain declaration badges", declarationCount >= 2)
            val declarationOffset = source.indexOf("private suspend fun readFromDisk")
            val declaration = editor.inlayModel.getBlockElementsInRange(declarationOffset, declarationOffset).single()
            val usages = editor.inlayModel.addBlockElement(
                declarationOffset, false, true, BlockInlayPriority.CODE_VISION_USAGES, declaration.renderer,
            )!!
            try {
                val line = editor.offsetToVisualPosition(declarationOffset).line
                assertEquals(
                    "Dispatcher badges belong between Code Vision usages and the declaration",
                    listOf(usages, declaration),
                    editor.inlayModel.getBlockElementsForVisualLine(line, true),
                )
            } finally {
                usages.dispose()
            }
            assertEquals(source, editor.document.text)
            fun declarationTooltip(): String = ReadAction.compute<String, RuntimeException> {
                val badge = analysis.requestAnalysis(file).declarations.getValue(source.indexOf("private suspend fun readFromDisk"))
                BadgeTooltip.create(badge.summary, badge.tooltip, declaration = true, scheme = editor.colorsScheme)
            }
            val initialTooltip = declarationTooltip()
            assertTrue(initialTooltip.contains(">IO</span>"))
            assertFalse(initialTooltip.contains(">Main</span>"))

            settings.updateCalls(CallSiteBadgeMode.DISPATCHER_CHANGES)
            myFixture.doHighlighting()
            awaitInlineOffsets(editor, source.length) {
                header !in it && delayCall !in it && readCall !in it && lambdaEnd !in it
            }
            assertEquals(declarationCount, editor.inlayModel.getBlockElementsInRange(0, source.length).size)

            editor.caretModel.moveToOffset(caller - 1)
            myFixture.doHighlighting()
            awaitInlineOffsets(editor, source.length) { caller in it && header !in it && lambdaEnd !in it }
            val initialCallerSummary = ReadAction.compute<EffectSummary, RuntimeException> {
                analysis.requestAnalysis(file).calls.getValue(caller).summary
            }
            assertEquals(setOf(Dispatcher.Main, Dispatcher.IO), initialCallerSummary.dispatchers.known)
            assertEquals(setOf(Dispatcher.IO), initialCallerSummary.selectedDispatchers.known)
            assertEquals(
                listOf("IO"),
                BadgePresentation.segments(initialCallerSummary, false, true).map { it.label },
            )
            assertTrue(BadgePresentation.segments(initialCallerSummary, false, true).single().partial)

            val revised = source.replace("Dispatchers.IO", "Dispatchers.Default")
            WriteCommandAction.runWriteCommandAction(project) {
                editor.document.setText(revised)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
            assertFalse(analysis.hasCurrentAnalysis(file))
            val revisedCaller = revised.lastIndexOf("mixedWorkload()") + "mixedWorkload()".length
            editor.caretModel.moveToOffset(revisedCaller - 1)
            FileDocumentManager.getInstance().saveDocument(editor.document)
            PlatformTestUtil.waitWithEventsDispatching(
                "Saved trailing-lambda analysis",
                { analysis.hasCurrentAnalysis(file) },
                30,
            )
            myFixture.doHighlighting()
            val revisedHeader = headerOffset(revised, "Default")
            val revisedLambdaEnd = lambdaEndOffset(revised)
            awaitInlineOffsets(editor, revised.length) {
                revisedCaller in it && caller !in it && revisedHeader !in it && revisedLambdaEnd !in it
            }
            val revisedCallerSummary = ReadAction.compute<EffectSummary, RuntimeException> {
                analysis.requestAnalysis(file).calls.getValue(revisedCaller).summary
            }
            assertEquals(setOf(Dispatcher.Main, Dispatcher.Default), revisedCallerSummary.dispatchers.known)
            assertEquals(setOf(Dispatcher.Default), revisedCallerSummary.selectedDispatchers.known)
            assertEquals(
                listOf("Default"),
                BadgePresentation.segments(revisedCallerSummary, false, true).map { it.label },
            )
            assertTrue(BadgePresentation.segments(revisedCallerSummary, false, true).single().partial)
            assertEquals(declarationCount, editor.inlayModel.getBlockElementsInRange(0, revised.length).size)
            assertEquals(revised, editor.document.text)
            val revisedTooltip = declarationTooltip()
            assertTrue(revisedTooltip.contains(">Default</span>"))
            assertFalse(revisedTooltip.contains(">IO</span>"))

            editor.caretModel.moveToOffset(revised.indexOf("delay(1)"))
            myFixture.doHighlighting()
            awaitInlineOffsets(editor, revised.length) {
                revisedCaller !in it && revisedHeader !in it && revisedLambdaEnd !in it
            }

            settings.updateCalls(CallSiteBadgeMode.NONE)
            myFixture.doHighlighting()
            awaitInlineOffsets(editor, revised.length) { revisedHeader !in it && revisedLambdaEnd !in it }
            assertEquals(declarationCount, editor.inlayModel.getBlockElementsInRange(0, revised.length).size)
            assertEquals(revised, editor.document.text)
        } finally {
            settings.loadState(saved)
        }
    }

    fun testCaretMovementRefreshesCallBadgesBetweenFunctions() {
        System.getProperty("dispatcher.fixture.libraries").split(File.pathSeparator).forEach { path ->
            val library = File(path)
            PsiTestUtil.addLibrary(myFixture.module, library.nameWithoutExtension, library.parent, library.name)
        }
        val settings = service<DispatcherSettings>()
        val saved = settings.getState()
        try {
            settings.loadState(
                DispatcherHintSettings(
                    callSiteMode = CallSiteBadgeMode.ALL,
                    onlyInFunctionContainingCaret = true,
                ),
            )
            val file = myFixture.addFileToProject("CaretBadges.kt", """
                private suspend fun work() = 42
                suspend fun first() { work() }
                suspend fun second() { work() }
            """.trimIndent()) as KtFile
            myFixture.configureFromExistingVirtualFile(file.virtualFile)
            FileDocumentManager.getInstance().saveAllDocuments()
            val analysis = project.service<DispatcherAnalysisService>()
            analysis.startAnalysis()
            PlatformTestUtil.waitWithEventsDispatching(
                "Initial caret-scope analysis",
                { analysis.hasCurrentAnalysis(file) },
                30,
            )

            val editor = myFixture.editor
            val length = editor.document.textLength
            val ranges = CaretFunctionScope.functionRanges(file)
            val firstCall = file.text.indexOf("work()", file.text.indexOf("suspend fun first"))
            val secondCall = file.text.indexOf("work()", file.text.indexOf("suspend fun second"))
            editor.caretModel.moveToOffset(firstCall)
            myFixture.doHighlighting()
            awaitInlineOffsets(editor, length) { firstCall + "work()".length in it && secondCall + "work()".length !in it }
            val declarationCount = editor.inlayModel.getBlockElementsInRange(0, length).size

            editor.caretModel.moveToOffset(firstCall + 2)
            assertEquals(
                CaretFunctionScope.selectedRange(ranges, firstCall),
                CaretFunctionScope.selectedRange(ranges, editor.caretModel.offset),
            )
            assertEquals(declarationCount, editor.inlayModel.getBlockElementsInRange(0, length).size)

            editor.caretModel.moveToOffset(secondCall)
            myFixture.doHighlighting()
            awaitInlineOffsets(editor, length) { secondCall + "work()".length in it && firstCall + "work()".length !in it }
            assertEquals(declarationCount, editor.inlayModel.getBlockElementsInRange(0, length).size)

            editor.caretModel.moveToOffset(file.text.indexOf("suspend fun second") - 1)
            myFixture.doHighlighting()
            awaitInlineOffsets(editor, length) { firstCall + "work()".length !in it && secondCall + "work()".length !in it }
            assertEquals(declarationCount, editor.inlayModel.getBlockElementsInRange(0, length).size)
            assertEquals(file.text, editor.document.text)
        } finally {
            settings.loadState(saved)
        }
    }

    fun testCallSiteModeChangesRefreshUnchangedEditor() {
        System.getProperty("dispatcher.fixture.libraries").split(File.pathSeparator).forEach { path ->
            val library = File(path)
            PsiTestUtil.addLibrary(myFixture.module, library.nameWithoutExtension, library.parent, library.name)
        }
        val settings = service<DispatcherSettings>()
        val saved = settings.getState()
        try {
            settings.loadState(DispatcherHintSettings(callSiteMode = CallSiteBadgeMode.NONE))
            val source = """
                import kotlinx.coroutines.Dispatchers
                import kotlinx.coroutines.delay
                import kotlinx.coroutines.withContext

                private suspend fun load( ) = withContext( Dispatchers.IO ){delay( 1 )}
                suspend fun refresh( ){
                    load( )
                    withContext( Dispatchers.IO ){load( )}
                }
            """.trimIndent()
            val file = myFixture.addFileToProject("LiveBadges.kt", source) as KtFile
            myFixture.configureFromExistingVirtualFile(file.virtualFile)
            FileDocumentManager.getInstance().saveAllDocuments()
            val analysis = project.service<DispatcherAnalysisService>()
            analysis.startAnalysis()
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
            PlatformTestUtil.waitWithEventsDispatching(
                "Dispatcher analysis should refresh hints after saved files are analyzed",
                { completed && analysis.hasCurrentAnalysis(file) },
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
            assertFalse(editor.document.text.contains("Dispatcher."))
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

    private fun awaitInlineOffsets(
        editor: com.intellij.openapi.editor.Editor,
        length: Int,
        condition: (List<Int>) -> Boolean,
    ) {
        PlatformTestUtil.waitWithEventsDispatching("Caret-scoped call inlays", {
            condition(editor.inlayModel.getInlineElementsInRange(0, length).map { it.offset })
        }, 20)
    }
}
