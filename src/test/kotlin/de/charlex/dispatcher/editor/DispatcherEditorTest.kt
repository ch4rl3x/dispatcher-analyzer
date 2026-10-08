package de.charlex.dispatcher.editor

import com.intellij.codeInsight.hints.BlockConstraints
import com.intellij.codeInsight.hints.HorizontalConstraints
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.codeInsight.hints.presentation.RootInlayPresentation
import com.intellij.codeInsight.hints.presentation.SequencePresentation
import com.intellij.codeInsight.hints.presentation.StaticDelegatePresentation
import com.intellij.codeInsight.hints.presentation.TextInlayPresentation
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.ColorUtil
import de.charlex.dispatcher.analysis.BadgeResult
import de.charlex.dispatcher.analysis.FileAnalysis
import de.charlex.dispatcher.model.Dispatcher
import de.charlex.dispatcher.model.DispatcherSet
import de.charlex.dispatcher.model.EffectSummary
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import javax.swing.JCheckBox
import javax.swing.JPanel

class DispatcherEditorTest : BasePlatformTestCase() {
    fun testDeclarationAndCallPositionsPreserveDocument() {
        val source = "suspend fun load() {}\nsuspend fun refresh() { load() }"
        myFixture.configureByText("Badge.kt", source)
        val end = source.lastIndexOf("load()") + "load()".length
        val result = FileAnalysis(
            declarations = mapOf(0 to badge(Dispatcher.Main)),
            calls = mapOf(end to badge(Dispatcher.IO)),
        )
        val sink = RecordingSink()

        render(result, DispatcherHintSettings(showCalls = true), sink)

        assertEquals(listOf(0), sink.blocks.map { it.offset })
        assertTrue(sink.blocks.single().above)
        assertEquals(listOf(end), sink.inline.map { it.offset })
        assertEquals("Dispatcher Main", sink.blocks.single().text.trim())
        assertEquals("Dispatcher IO", sink.inline.single().text.trim())
        assertEquals(source, myFixture.editor.document.text)
        assertEquals(2, myFixture.editor.document.lineCount)
    }

    fun testCallToggleNeverHidesDeclarations() {
        myFixture.configureByText("Badge.kt", "suspend fun load() { load() }")
        val result = FileAnalysis(mapOf(0 to badge(Dispatcher.Main)), mapOf(25 to badge(Dispatcher.IO)))
        val declarations = RecordingSink()
        render(result, DispatcherHintSettings(), declarations)
        assertEquals(1, declarations.blocks.size)
        assertTrue(declarations.inline.isEmpty())

        val calls = RecordingSink()
        render(result, DispatcherHintSettings(showCalls = true), calls)
        assertEquals(1, calls.blocks.size)
        assertEquals(1, calls.inline.size)
        assertFalse(DispatcherInlayProvider().isVisibleInSettings)
    }

    fun testDedicatedSettingsPersistCallToggle() {
        val settings = service<DispatcherSettings>()
        val saved = settings.getState()
        val configurable = DispatcherConfigurable()
        try {
            settings.loadState(DispatcherHintSettings())
            val component = configurable.createComponent() as JPanel
            val checkbox = component.getComponent(0) as JCheckBox
            val automatic = component.getComponent(1) as JCheckBox
            assertFalse(checkbox.isSelected)
            assertTrue(automatic.isSelected)
            assertFalse(configurable.isModified())
            checkbox.doClick()
            assertTrue(configurable.isModified())
            configurable.apply()
            assertTrue(settings.showCalls)
            assertTrue(settings.automaticAnalysis)
            assertFalse(configurable.isModified())
            val restored = DispatcherSettings()
            restored.loadState(settings.getState())
            assertTrue(restored.showCalls)
            automatic.isSelected = false
            configurable.apply()
            assertFalse(settings.automaticAnalysis)
            settings.updateCalls(false)
            assertFalse(settings.automaticAnalysis)
            checkbox.isSelected = false
            configurable.reset()
            assertFalse(checkbox.isSelected)
            assertFalse(automatic.isSelected)
        } finally {
            settings.loadState(saved)
            configurable.disposeUIResources()
        }
    }

    fun testFreshResultsReplaceCertaintyWithUnknown() {
        myFixture.configureByText("Badge.kt", "suspend fun load() {}")
        val known = RecordingSink()
        render(FileAnalysis(mapOf(0 to badge(Dispatcher.IO))), DispatcherHintSettings(), known)
        assertTrue(known.blocks.single().text.contains("IO"))

        val unknown = BadgeResult(EffectSummary(DispatcherSet.unknown("Indexing")), "Analysis is waiting for indexing.")
        val refreshed = RecordingSink()
        render(FileAnalysis(mapOf(0 to unknown)), DispatcherHintSettings(), refreshed)
        assertTrue(refreshed.blocks.single().text.contains("Unknown"))
        assertFalse(refreshed.blocks.single().text.contains("IO"))
    }

    fun testMixedEntriesUseTheirOwnColorsAndLabels() {
        myFixture.configureByText("Badge.kt", "suspend fun load() {}")
        val summary = EffectSummary(
            DispatcherSet.of(Dispatcher.Main, Dispatcher.IO).join(DispatcherSet.unknown("Unresolved branch")),
        )
        val presentation = BadgePresentation.create(
            PresentationFactory(myFixture.editor), myFixture.editor, summary, "Mixed dispatchers.", false,
        )
        assertEquals("Dispatcher Main (partial) | IO (partial) | Unknown", textOf(presentation))
        val dark = ColorUtil.isDark(myFixture.editor.colorsScheme.defaultBackground)
        assertEquals(
            listOf(
                "Main (partial)" to BadgeColor.MAIN.color(dark),
                "IO (partial)" to BadgeColor.IO.color(dark),
                "Unknown" to BadgeColor.UNKNOWN.color(dark),
            ),
            coloredEntries(presentation),
        )
    }

    fun testColoredTextPaintsItsForegroundWithoutMutatingInput() {
        myFixture.configureByText("Badge.kt", "suspend fun load() {}")
        val presentation = ColoredTextPresentation(
            PresentationFactory(myFixture.editor).smallTextWithoutBackground("MMMM"), Color.RED,
        )
        val image = BufferedImage(presentation.width, presentation.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        val original = TextAttributes(Color.BLUE, null, null, null, Font.PLAIN)
        try {
            presentation.paint(graphics, original)
        } finally {
            graphics.dispose()
        }
        assertEquals(Color.BLUE, original.foregroundColor)
        assertTrue((0 until image.width).any { x ->
            (0 until image.height).any { y -> image.getRGB(x, y) == Color.RED.rgb }
        })
    }

    private fun badge(dispatcher: Dispatcher) = BadgeResult(
        EffectSummary(DispatcherSet.of(dispatcher)),
        "Resolved dispatcher.",
    )

    private fun render(result: FileAnalysis, settings: DispatcherHintSettings, sink: RecordingSink) {
        BadgePresentation.render(PresentationFactory(myFixture.editor), result, myFixture.editor, settings, sink)
    }

    private data class RecordedHint(val offset: Int, val above: Boolean, val text: String)

    private class RecordingSink : InlayHintsSink {
        val blocks = mutableListOf<RecordedHint>()
        val inline = mutableListOf<RecordedHint>()

        override fun addInlineElement(
            offset: Int,
            relatesToPrecedingText: Boolean,
            presentation: InlayPresentation,
            placeAtTheEndOfLine: Boolean,
        ) {
            inline += RecordedHint(offset, false, textOf(presentation))
        }

        override fun addBlockElement(
            offset: Int,
            relatesToPrecedingText: Boolean,
            showAbove: Boolean,
            priority: Int,
            presentation: InlayPresentation,
        ) {
            blocks += RecordedHint(offset, showAbove, textOf(presentation))
        }

        override fun addInlineElement(
            offset: Int,
            presentation: RootInlayPresentation<*>,
            constraints: HorizontalConstraints?,
        ) {
            error("Unexpected root presentation")
        }

        override fun addBlockElement(
            logicalLine: Int,
            showAbove: Boolean,
            presentation: RootInlayPresentation<*>,
            constraints: BlockConstraints?,
        ) {
            error("Unexpected root presentation")
        }
    }

    companion object {
        private fun textOf(presentation: InlayPresentation): String = when (presentation) {
            is TextInlayPresentation -> presentation.text
            is SequencePresentation -> presentation.presentations.joinToString("") { textOf(it) }
            is StaticDelegatePresentation -> textOf(presentation.presentation)
            else -> ""
        }

        private fun coloredEntries(presentation: InlayPresentation): List<Pair<String, Color>> = when {
            presentation is ColoredTextPresentation -> {
                listOf(textOf(presentation) to presentation.color)
            }
            presentation is SequencePresentation -> presentation.presentations.flatMap { coloredEntries(it) }
            presentation is StaticDelegatePresentation -> coloredEntries(presentation.presentation)
            else -> emptyList()
        }
    }
}
