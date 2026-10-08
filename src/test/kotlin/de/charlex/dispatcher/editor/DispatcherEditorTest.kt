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
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.ui.ColorUtil
import de.charlex.dispatcher.analysis.BadgeResult
import de.charlex.dispatcher.analysis.FileAnalysis
import de.charlex.dispatcher.model.Dispatcher
import de.charlex.dispatcher.model.DispatcherOrigin
import de.charlex.dispatcher.model.DispatcherSet
import de.charlex.dispatcher.model.EffectSummary
import java.awt.Color
import java.awt.Font
import java.awt.Point
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JCheckBox
import javax.swing.JComboBox
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
        assertEquals("called within Dispatcher Main", sink.blocks.single().text.trim())
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

    fun testCallInsideUncalledFunctionKeepsItsUnknownBadge() {
        val source = "suspend fun unused() { delay(1) }"
        myFixture.configureByText("Badge.kt", source)
        val offset = source.indexOf("delay(1)") + "delay(1)".length
        val unknown = BadgeResult(
            EffectSummary(DispatcherSet.unknown("No proven callers were found")),
            "The inherited dispatcher is unknown.",
        )
        val sink = RecordingSink()
        render(FileAnalysis(calls = mapOf(offset to unknown)), DispatcherHintSettings(showCalls = true), sink)
        assertTrue(sink.blocks.isEmpty())
        assertEquals("Dispatcher Unknown", sink.inline.single().text.trim())
        assertEquals(source, myFixture.editor.document.text)
    }

    fun testCallSiteBadgeModesFilterCallsWithoutHidingDeclarations() {
        val source = "suspend fun refresh() { inherited(); explicit(); unresolved() }"
        myFixture.configureByText("Badge.kt", source)
        val inheritedOffset = source.indexOf("inherited()") + "inherited()".length
        val explicitOffset = source.indexOf("explicit()") + "explicit()".length
        val unresolvedOffset = source.indexOf("unresolved()") + "unresolved()".length
        val inherited = badge(Dispatcher.Main)
        val explicit = BadgeResult(
            EffectSummary(DispatcherSet.of(Dispatcher.IO), setsDispatcher = true),
            "The callee selects IO.",
        )
        val unresolved = BadgeResult(
            EffectSummary(DispatcherSet.unknown("Unresolved")),
            "The callee dispatcher is unknown.",
        )
        val result = FileAnalysis(
            declarations = mapOf(0 to inherited),
            calls = mapOf(
                inheritedOffset to inherited,
                explicitOffset to explicit,
                unresolvedOffset to unresolved,
            ),
        )

        val all = RecordingSink()
        render(result, DispatcherHintSettings(callSiteMode = CallSiteBadgeMode.ALL), all)
        assertEquals(listOf(inheritedOffset, explicitOffset, unresolvedOffset), all.inline.map { it.offset })
        assertEquals(1, all.blocks.size)

        val dispatcherChanges = RecordingSink()
        render(result, DispatcherHintSettings(callSiteMode = CallSiteBadgeMode.DISPATCHER_CHANGES), dispatcherChanges)
        assertEquals(listOf(explicitOffset), dispatcherChanges.inline.map { it.offset })
        assertEquals(1, dispatcherChanges.blocks.size)

        val hidden = RecordingSink()
        render(result, DispatcherHintSettings(callSiteMode = CallSiteBadgeMode.NONE), hidden)
        assertTrue(hidden.inline.isEmpty())
        assertEquals(1, hidden.blocks.size)
        assertEquals(source, myFixture.editor.document.text)
    }

    fun testDedicatedSettingsPersistCallToggle() {
        val settings = service<DispatcherSettings>()
        val saved = settings.getState()
        val configurable = DispatcherConfigurable()
        try {
            settings.loadState(DispatcherHintSettings())
            val component = configurable.createComponent() as JPanel
            val callSiteRow = component.getComponent(0) as JPanel
            val selector = callSiteRow.getComponent(1) as JComboBox<*>
            val automatic = component.getComponent(1) as JCheckBox
            assertEquals(CallSiteBadgeMode.NONE, selector.selectedItem)
            assertEquals(
                listOf("All calls", "Only calls that set a dispatcher", "Do not show (default)"),
                CallSiteBadgeMode.entries.map { it.displayName },
            )
            assertTrue(automatic.isSelected)
            assertFalse(configurable.isModified())
            selector.selectedItem = CallSiteBadgeMode.ALL
            assertTrue(configurable.isModified())
            configurable.apply()
            assertEquals(CallSiteBadgeMode.ALL, settings.callSiteMode)
            assertTrue(settings.showCalls)
            assertTrue(settings.automaticAnalysis)
            assertFalse(configurable.isModified())
            val restored = DispatcherSettings()
            restored.loadState(settings.getState())
            assertEquals(CallSiteBadgeMode.ALL, restored.callSiteMode)
            assertTrue(restored.showCalls)
            automatic.isSelected = false
            configurable.apply()
            assertFalse(settings.automaticAnalysis)
            settings.updateCalls(false)
            assertEquals(CallSiteBadgeMode.NONE, settings.callSiteMode)
            assertFalse(settings.automaticAnalysis)
            selector.selectedItem = CallSiteBadgeMode.DISPATCHER_CHANGES
            configurable.reset()
            assertEquals(CallSiteBadgeMode.NONE, selector.selectedItem)
            assertFalse(automatic.isSelected)
        } finally {
            settings.loadState(saved)
            configurable.disposeUIResources()
        }
    }

    fun testLegacyCallVisibilityMigratesUnlessAnExplicitModeExists() {
        val settings = DispatcherSettings()
        val legacyState = XmlSerializer.deserialize(
            XmlSerializer.serialize(DispatcherHintSettings(showCalls = true)),
            DispatcherHintSettings::class.java,
        )
        assertTrue(legacyState.showCalls)
        assertNull(legacyState.callSiteMode)
        settings.loadState(legacyState)
        assertEquals(CallSiteBadgeMode.ALL, settings.callSiteMode)
        assertEquals(CallSiteBadgeMode.ALL, settings.getState().callSiteMode)

        val serialized = XmlSerializer.serialize(settings.getState())
        val restored = DispatcherSettings().apply {
            loadState(XmlSerializer.deserialize(serialized, DispatcherHintSettings::class.java))
        }
        assertEquals(CallSiteBadgeMode.ALL, restored.callSiteMode)

        settings.loadState(DispatcherHintSettings(showCalls = false))
        assertEquals(CallSiteBadgeMode.NONE, settings.callSiteMode)
        settings.loadState(
            DispatcherHintSettings(showCalls = true, callSiteMode = CallSiteBadgeMode.DISPATCHER_CHANGES),
        )
        assertEquals(CallSiteBadgeMode.DISPATCHER_CHANGES, settings.callSiteMode)
        assertTrue(settings.showCalls)
        settings.loadState(DispatcherHintSettings(showCalls = true, callSiteMode = CallSiteBadgeMode.NONE))
        assertEquals(CallSiteBadgeMode.NONE, settings.callSiteMode)
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
        val declaration = BadgePresentation.create(
            PresentationFactory(myFixture.editor), myFixture.editor, summary, "Mixed dispatchers.", true,
        )
        assertEquals("called within Dispatcher Main | IO | Unknown", textOf(declaration))
        val dark = ColorUtil.isDark(myFixture.editor.colorsScheme.defaultBackground)
        val expectedColors = listOf(
            "Main" to BadgeColor.MAIN.color(dark),
            "IO" to BadgeColor.IO.color(dark),
            "Unknown" to BadgeColor.UNKNOWN.color(dark),
        )
        assertEquals(
            listOf(
                "Main" to BadgeColor.MAIN.color(dark),
                " (partial)" to BadgeColor.MAIN.color(dark),
                "IO" to BadgeColor.IO.color(dark),
                " (partial)" to BadgeColor.IO.color(dark),
                "Unknown" to BadgeColor.UNKNOWN.color(dark),
            ),
            coloredEntries(presentation),
        )
        assertEquals(expectedColors, coloredEntries(declaration))
    }

    fun testUnknownDeclarationUsesIncomingContextWording() {
        myFixture.configureByText("Badge.kt", "suspend fun load() {}")
        val presentation = BadgePresentation.create(
            PresentationFactory(myFixture.editor),
            myFixture.editor,
            EffectSummary.EMPTY,
            "No dispatcher evidence.",
            true,
        )
        assertEquals("called within Dispatcher Unknown", textOf(presentation))
        val dark = ColorUtil.isDark(myFixture.editor.colorsScheme.defaultBackground)
        assertEquals(listOf("Unknown" to BadgeColor.UNKNOWN.color(dark)), coloredEntries(presentation))
    }

    fun testOnlyDispatcherNamesNavigateToTheirOwnOrigins() {
        myFixture.configureByText("Badge.kt", "suspend fun load() {}")
        val factory = PresentationFactory(myFixture.editor)
        val mainOrigin = DispatcherOrigin("file:///project/Main.kt", 12, 3, "called from Main")
        val ioOrigin = DispatcherOrigin("file:///project/Io.kt", 26, 5, "called from IO")
        val summary = EffectSummary(
            DispatcherSet(
                known = setOf(Dispatcher.Main, Dispatcher.IO),
                unknownReasons = setOf("Unresolved path"),
                origins = mapOf(
                    Dispatcher.Main to setOf(mainOrigin),
                    Dispatcher.IO to setOf(ioOrigin),
                ),
            ),
        )
        val navigated = mutableListOf<Pair<Dispatcher, DispatcherOrigin>>()
        val presentation = BadgePresentation.create(
            factory,
            myFixture.editor,
            summary,
            "Mixed dispatchers.",
            declaration = false,
            isCurrentAnalysis = { true },
            navigateDispatcher = { dispatcher, origins, current ->
                DispatcherOriginNavigation.navigate(
                    myFixture.editor,
                    origins,
                    current,
                    openOrigin = { origin -> navigated += dispatcher to origin },
                )
            },
        )
        val leftInset = 3
        val prefixWidth = factory.smallTextWithoutBackground("Dispatcher ").width
        val mainWidth = factory.smallTextWithoutBackground("Main").width
        val partialWidth = factory.smallTextWithoutBackground(" (partial)").width
        val separatorWidth = factory.smallTextWithoutBackground(" | ").width
        val ioWidth = factory.smallTextWithoutBackground("IO").width
        val unknownWidth = factory.smallTextWithoutBackground("Unknown").width
        val mainStart = leftInset + prefixWidth
        val partialMainStart = mainStart + mainWidth
        val separatorStart = partialMainStart + partialWidth
        val ioStart = separatorStart + separatorWidth
        val partialIoStart = ioStart + ioWidth
        val unknownStart = partialIoStart + partialWidth + separatorWidth

        clickAt(presentation, mainStart + mainWidth / 2)
        assertEquals(listOf(Dispatcher.Main to mainOrigin), navigated)
        clickAt(presentation, partialMainStart + partialWidth / 2)
        clickAt(presentation, separatorStart + separatorWidth / 2)
        clickAt(presentation, ioStart + ioWidth / 2)
        assertEquals(
            listOf(Dispatcher.Main to mainOrigin, Dispatcher.IO to ioOrigin),
            navigated,
        )
        clickAt(presentation, partialIoStart + partialWidth / 2)
        clickAt(presentation, unknownStart + unknownWidth / 2)
        clickAt(presentation, leftInset + prefixWidth / 2)
        assertEquals(2, navigated.size)
    }

    fun testStaleBadgeAndStalePopupSelectionDoNotNavigate() {
        myFixture.configureByText("Badge.kt", "suspend fun load() {}")
        val origin = DispatcherOrigin("file:///project/Main.kt", 12, 3, "called from Main")
        val opened = mutableListOf<DispatcherOrigin>()
        var current = false
        DispatcherOriginNavigation.navigate(
            myFixture.editor,
            listOf(origin),
            isCurrentAnalysis = { current },
            openOrigin = opened::add,
        )
        assertTrue(opened.isEmpty())

        val secondOrigin = origin.copy(offset = 22, line = 4, description = "another caller")
        var chooseOrigin: ((DispatcherOrigin) -> Unit)? = null
        current = true
        DispatcherOriginNavigation.navigate(
            myFixture.editor,
            listOf(origin, secondOrigin),
            isCurrentAnalysis = { current },
            openOrigin = opened::add,
            showChooser = { choices, onChosen ->
                assertEquals(2, choices.size)
                chooseOrigin = onChosen
            },
        )
        current = false
        chooseOrigin?.invoke(origin)
        assertTrue(opened.isEmpty())
    }

    fun testNavigationAcceptsUnsavedDocumentOffsetsAndRejectsOffsetsPastDocument() {
        myFixture.configureByText("Badge.kt", "suspend fun load() {}")
        val file = myFixture.file.virtualFile
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.textLength, "\nsuspend fun appended() {}")
        }
        val validOffset = document.textLength
        assertTrue(validOffset.toLong() > file.length)
        val valid = DispatcherOrigin(file.url, validOffset, 2, "appended caller")

        DispatcherOriginNavigation.navigate(myFixture.editor, listOf(valid), { true })

        assertEquals(validOffset, myFixture.editor.caretModel.offset)
        myFixture.editor.caretModel.moveToOffset(0)
        val invalid = valid.copy(offset = document.textLength + 1)
        DispatcherOriginNavigation.navigate(myFixture.editor, listOf(invalid), { true })
        assertEquals(0, myFixture.editor.caretModel.offset)
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

    private fun clickAt(presentation: InlayPresentation, x: Int) {
        val y = presentation.height / 2
        val moved = MouseEvent(
            myFixture.editor.contentComponent,
            MouseEvent.MOUSE_MOVED,
            System.currentTimeMillis(),
            0,
            x,
            y,
            0,
            false,
        )
        val clicked = MouseEvent(
            myFixture.editor.contentComponent,
            MouseEvent.MOUSE_CLICKED,
            System.currentTimeMillis(),
            0,
            x,
            y,
            1,
            false,
            MouseEvent.BUTTON1,
        )
        val point = Point(x, y)
        presentation.mouseMoved(moved, point)
        presentation.mouseClicked(clicked, point)
        presentation.mouseExited()
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
