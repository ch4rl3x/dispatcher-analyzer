package de.charlex.dispatcher.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import de.charlex.dispatcher.analysis.DispatcherAnalysisListener
import de.charlex.dispatcher.analysis.DispatcherAnalysisService
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

class DispatcherToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val status = JLabel()
        val mode = JLabel()
        val run = JButton("Analyze project")
        val settingsButton = JButton("Settings…")
        val analysis = project.service<DispatcherAnalysisService>()
        fun refresh() {
            status.text = "Status: ${analysis.analysisStatus}"
            mode.text = if (service<DispatcherSettings>().automaticAnalysis) {
                "Automatic analysis is on."
            } else {
                "Automatic analysis is off."
            }
            run.isEnabled = analysis.analysisStatus != "Analyzing"
        }
        val panel = JPanel(BorderLayout(0, 12)).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(status)
                add(mode)
            }, BorderLayout.NORTH)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(run)
                add(settingsButton)
            }, BorderLayout.CENTER)
        }
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        project.messageBus.connect(content).subscribe(
            DispatcherAnalysisListener.TOPIC,
            object : DispatcherAnalysisListener {
                override fun analysisUpdated() = refresh()
            },
        )
        run.addActionListener {
            analysis.runAnalysisNow()
            refresh()
        }
        settingsButton.addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, DispatcherConfigurable::class.java)
        }
        refresh()
        toolWindow.contentManager.addContent(content)
    }
}
