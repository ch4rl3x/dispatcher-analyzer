package de.charlex.dispatcher.editor

import com.intellij.codeInsight.hints.LinearOrderInlayRenderer
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.RecursivelyUpdatingRootPresentation
import com.intellij.codeInsight.hints.presentation.SequencePresentation
import com.intellij.codeInsight.hints.presentation.StatefulPresentation
import com.intellij.codeInsight.hints.presentation.StaticDelegatePresentation
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.charlex.dispatcher.analysis.DispatcherAnalysisService
import org.jetbrains.kotlin.psi.KtFile
import java.awt.Color

class DispatcherColorRefreshTest : BasePlatformTestCase() {
    fun testSchemeChangesRefreshExistingInlaysWithoutAnalysisOrSourceEdits() {
        val manager = EditorColorsManager.getInstance()
        val originalScheme = manager.globalScheme
        val settings = service<DispatcherSettings>()
        val originalSettings = settings.getState()
        try {
            settings.loadState(DispatcherHintSettings(callSiteMode = CallSiteBadgeMode.ALL))
            val source = "private suspend fun work() = 42\nsuspend fun caller() = work()"
            val file = myFixture.addFileToProject("Colors.kt", source) as KtFile
            myFixture.configureFromExistingVirtualFile(file.virtualFile)
            FileDocumentManager.getInstance().saveAllDocuments()
            val analysis = project.service<DispatcherAnalysisService>()
            analysis.startAnalysis()
            PlatformTestUtil.waitWithEventsDispatching("Initial analysis", { analysis.hasCurrentAnalysis(file) }, 30)
            val result = ReadAction.compute<de.charlex.dispatcher.analysis.FileAnalysis, RuntimeException> {
                analysis.requestAnalysis(file)
            }
            val editor = myFixture.editor
            fun inlays() = editor.inlayModel.getBlockElementsInRange(0, source.length) +
                editor.inlayModel.getInlineElementsInRange(0, source.length)
            myFixture.doHighlighting()
            val offsets = inlays().map { it.offset }
            assertEquals(2, offsets.size)
            for (newColor in listOf(Color(0x2468AC), Color(0xAB7531))) {
                val scheme = originalScheme.clone() as EditorColorsScheme
                scheme.name = "Dispatcher test ${newColor.rgb}"
                scheme.setColor(BadgeColor.UNKNOWN.key, newColor)
                manager.setGlobalScheme(scheme)
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                myFixture.doHighlighting()
                PlatformTestUtil.waitWithEventsDispatching("Recolored inlays", {
                    val colors = inlays().flatMap {
                        colors((it.renderer as LinearOrderInlayRenderer<*>).getCachedPresentation())
                    }
                    colors.size == 2 && colors.all { it == newColor }
                }, 15)
                assertEquals(offsets, inlays().map { it.offset })
                assertEquals(source, editor.document.text)
                assertTrue(analysis.isCurrentAnalysis(file.virtualFile.url, result))
            }
        } finally {
            manager.setGlobalScheme(originalScheme)
            settings.loadState(originalSettings)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
    }

    private fun colors(presentation: InlayPresentation): List<Color> = when (presentation) {
        is ColoredTextPresentation -> listOf(presentation.color)
        is SequencePresentation -> presentation.presentations.flatMap(::colors)
        is StaticDelegatePresentation -> colors(presentation.presentation)
        is StatefulPresentation<*> -> colors(presentation.currentPresentation)
        is RecursivelyUpdatingRootPresentation -> colors(presentation.content)
        else -> emptyList()
    }
}
