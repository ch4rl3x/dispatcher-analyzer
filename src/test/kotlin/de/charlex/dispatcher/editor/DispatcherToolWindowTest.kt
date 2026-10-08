package de.charlex.dispatcher.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import de.charlex.dispatcher.analysis.DispatcherAnalysisService
import org.jetbrains.kotlin.psi.KtFile
import java.awt.Component
import java.awt.Container
import java.io.File
import javax.swing.JButton
import javax.swing.JLabel

class DispatcherToolWindowTest : BasePlatformTestCase() {
    override fun getProjectDescriptor() = LightJavaCodeInsightFixtureTestCase.JAVA_21

    fun testManualRunUpdatesStatusAndPreservesCallSetting() {
        System.getProperty("dispatcher.fixture.libraries").split(File.pathSeparator).forEach { path ->
            val library = File(path)
            PsiTestUtil.addLibrary(myFixture.module, library.nameWithoutExtension, library.parent, library.name)
        }
        val settings = service<DispatcherSettings>()
        val saved = settings.getState()
        val manager = ToolWindowManager.getInstance(project)
        val id = "Dispatcher Analyzer Test"
        try {
            settings.loadState(DispatcherHintSettings(showCalls = true, automaticAnalysis = false))
            val file = myFixture.configureByText(
                "ManualAnalysis.kt", "private suspend fun load() = 42\nsuspend fun refresh() = load()",
            ) as KtFile
            val analysis = project.service<DispatcherAnalysisService>()
            analysis.analysisModeChanged()
            analysis.requestAnalysis(file)
            assertFalse(analysis.hasCurrentAnalysis(file))

            val window = manager.registerToolWindow(id) { anchor = ToolWindowAnchor.RIGHT }
            DispatcherToolWindowFactory().createToolWindowContent(project, window)
            val components = descendants(window.contentManager.contents.single().component).toList()
            val buttons = components.filterIsInstance<JButton>()
            val button = buttons.single { it.text == "Analyze project" }
            val settingsButton = buttons.single { it.text == "Settings…" }
            val status = components.filterIsInstance<JLabel>().single { it.text.startsWith("Status:") }
            val mode = components.filterIsInstance<JLabel>().single { it.text.startsWith("Automatic analysis") }
            assertEquals("Analyze project", button.text)
            assertEquals("Settings…", settingsButton.text)
            assertEquals("Automatic analysis is off.", mode.text)

            button.doClick()
            assertTrue(status.text in setOf("Status: Queued", "Status: Analyzing"))
            assertFalse(analysis.hasCurrentAnalysis(file))
            PlatformTestUtil.waitWithEventsDispatching(
                "Manual analysis should update the tool window after completion",
                { analysis.hasCurrentAnalysis(file) && status.text == "Status: Up to date" },
                30,
            )
            assertTrue(button.isEnabled)
            assertTrue(settings.showCalls)
            assertFalse(settings.automaticAnalysis)

            settings.update(showCalls = true, automaticAnalysis = true)
            PlatformTestUtil.waitWithEventsDispatching(
                "Automatic mode should update the tool window",
                { mode.text == "Automatic analysis is on." && status.text == "Status: Up to date" },
                30,
            )
            assertTrue(analysis.hasCurrentAnalysis(file))

            settings.update(showCalls = true, automaticAnalysis = false)
            PlatformTestUtil.waitWithEventsDispatching(
                "Mode changes should publish even when the snapshot remains current",
                { mode.text == "Automatic analysis is off." && status.text == "Status: Up to date" },
                5,
            )
            assertTrue(analysis.hasCurrentAnalysis(file))
            assertTrue(settings.showCalls)
        } finally {
            if (manager.getToolWindow(id) != null) manager.unregisterToolWindow(id)
            settings.loadState(saved)
        }
    }

    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
    }
}
