package de.charlex.dispatcher.editor

import com.intellij.codeInsight.hint.HintUtil
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.charlex.dispatcher.model.Dispatcher
import de.charlex.dispatcher.model.DispatcherSet
import de.charlex.dispatcher.model.EffectSummary
import javax.swing.JEditorPane
import javax.swing.text.GlyphView
import javax.swing.text.View

class BadgeTooltipTest : BasePlatformTestCase() {
    fun testSingleAndMixedIncomingContextsRenderWithThemeColors() {
        for (dark in listOf(false, true)) {
            val single = render(EffectSummary(DispatcherSet.of(Dispatcher.IO)), dark = dark)
            assertEquals("This function is called from Dispatcher IO.", text(single))
            assertColor(single, "IO", BadgeColor.IO, dark)

            val mixed = render(EffectSummary(DispatcherSet.of(Dispatcher.IO, Dispatcher.Main)), dark = dark)
            assertEquals("This function is called from Dispatcher Main and IO.", text(mixed))
            assertColor(mixed, "Main", BadgeColor.MAIN, dark)
            assertColor(mixed, "IO", BadgeColor.IO, dark)
            assertFalse(text(mixed).contains("partial"))
        }
    }

    fun testUnknownAndCustomLabelsKeepLiteralTextAndDiagnostics() {
        val custom = Dispatcher.Custom("worker", "Worker <IO> & \"Main\"")
        val summary = EffectSummary(
            DispatcherSet.of(Dispatcher.Main, custom).join(DispatcherSet.unknown("Missing <caller>")),
        )
        val details = "Missing <caller> & pending analysis."
        val document = render(summary, details)
        assertTrue(text(document).startsWith(
            "This function is called from Dispatcher Main, Worker <IO> & \"Main\" and Unknown.",
        ))
        assertTrue(text(document).contains(details))
        assertColor(document, custom.label, BadgeColor.CUSTOM, false)
        assertColor(document, "Unknown", BadgeColor.UNKNOWN, false)
    }

    fun testUnavailableResultsReplaceKnownContextsAndPreserveTheReason() {
        val known = render(EffectSummary(DispatcherSet.of(Dispatcher.IO)))
        assertTrue(text(known).contains("Dispatcher IO."))
        val unavailable = render(
            EffectSummary(DispatcherSet.unknown("Analysis is pending")),
            "Analysis is pending",
        )
        assertTrue(text(unavailable).startsWith("This function is called from Dispatcher Unknown."))
        assertTrue(text(unavailable).contains("Analysis is pending"))
        assertFalse(text(unavailable).contains("IO"))
        assertColor(unavailable, "Unknown", BadgeColor.UNKNOWN, false)
        assertEquals(
            "This function is called from Dispatcher Unknown.",
            text(render(EffectSummary.EMPTY)),
        )
    }

    fun testCallDescriptionsDistinguishSwitchesFromInheritedWork() {
        val mixed = EffectSummary(
            DispatcherSet.of(Dispatcher.Main, Dispatcher.IO),
            selectedDispatchers = DispatcherSet.of(Dispatcher.IO),
        )
        val selected = render(mixed, declaration = false)
        assertEquals("This function switches to Dispatcher IO.", text(selected))
        assertColor(selected, "IO", BadgeColor.IO, false)

        val both = render(
            EffectSummary(mixed.dispatchers, selectedDispatchers = mixed.dispatchers),
            declaration = false,
        )
        assertEquals("This function switches to Dispatcher Main and IO.", text(both))
        assertColor(both, "Main", BadgeColor.MAIN, false)
        assertColor(both, "IO", BadgeColor.IO, false)

        val inherited = render(EffectSummary(DispatcherSet.of(Dispatcher.Default)), declaration = false)
        assertEquals("This function runs on Dispatcher Default.", text(inherited))
        assertColor(inherited, "Default", BadgeColor.DEFAULT, false)
    }

    fun testUnknownSelectionsAndExecutionRetainUncertaintyDetails() {
        val unknown = DispatcherSet.unknown("Unresolved <dispatcher>")
        val selected = render(
            EffectSummary(DispatcherSet.of(Dispatcher.IO), selectedDispatchers = unknown),
            "Dispatcher selection is uncertain: Unresolved <dispatcher>",
            declaration = false,
        )
        assertTrue(text(selected).startsWith("This function switches to Dispatcher Unknown."))
        assertTrue(text(selected).contains("Unresolved <dispatcher>"))
        assertColor(selected, "Unknown", BadgeColor.UNKNOWN, false)

        val pending = render(EffectSummary(unknown), "Analysis is pending.", declaration = false)
        assertTrue(text(pending).startsWith("This function runs on Dispatcher Unknown."))
        assertTrue(text(pending).contains("Analysis is pending."))
    }

    private fun render(
        summary: EffectSummary,
        details: String = "",
        dark: Boolean = false,
        declaration: Boolean = true,
    ): JEditorPane {
        val html = BadgeTooltip.create(
            summary, details, declaration,
            requireNotNull(EditorColorsManager.getInstance().getScheme(if (dark) "Darcula" else "Default")),
        )
        val label = HintUtil.createInformationLabel(html) as HintUtil.HintLabel
        return requireNotNull(label.pane).apply { setSize(900, 200) }
    }

    private fun text(pane: JEditorPane) = pane.document.getText(0, pane.document.length).trim()

    private fun assertColor(pane: JEditorPane, label: String, color: BadgeColor, dark: Boolean) {
        val start = pane.document.getText(0, pane.document.length).indexOf(label)
        assertTrue("Missing tooltip label: $label", start >= 0)
        val root = pane.ui.getRootView(pane)
        root.setSize(pane.width.toFloat(), pane.height.toFloat())
        fun glyphs(view: View): List<GlyphView> = if (view is GlyphView) listOf(view) else
            (0 until view.viewCount).flatMap { glyphs(view.getView(it)) }
        val rendered = glyphs(root)
        // Check the renderer's color before platform-specific font antialiasing blends pixels.
        for (offset in start until start + label.length) {
            val glyph = rendered.firstOrNull { offset in it.startOffset until it.endOffset }
            assertNotNull("Missing rendered text for $label at $offset", glyph)
            assertEquals("Wrong rendered color for $label at $offset", color.color(dark), glyph!!.foreground)
        }
    }
}
