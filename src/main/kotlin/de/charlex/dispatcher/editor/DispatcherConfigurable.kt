package de.charlex.dispatcher.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.options.SearchableConfigurable
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

class DispatcherConfigurable : SearchableConfigurable {
    private var callHints: JCheckBox? = null
    private var automaticAnalysis: JCheckBox? = null

    override fun getId() = "dispatcher.analyzer"

    override fun getDisplayName() = "Dispatcher Analyzer"

    override fun createComponent(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        val settings = service<DispatcherSettings>()
        val checkbox = JCheckBox(
            "Show execution dispatcher badges at suspend calls",
            settings.showCalls,
        )
        callHints = checkbox
        add(checkbox)
        val automatic = JCheckBox("Analyze automatically after typing pauses", settings.automaticAnalysis)
        automaticAnalysis = automatic
        add(automatic)
    }

    override fun isModified(): Boolean {
        val settings = service<DispatcherSettings>()
        return callHints?.isSelected?.let { it != settings.showCalls } == true ||
            automaticAnalysis?.isSelected?.let { it != settings.automaticAnalysis } == true
    }

    override fun apply() {
        val calls = callHints ?: return
        val automatic = automaticAnalysis ?: return
        service<DispatcherSettings>().update(calls.isSelected, automatic.isSelected)
    }

    override fun reset() {
        callHints?.isSelected = service<DispatcherSettings>().showCalls
        automaticAnalysis?.isSelected = service<DispatcherSettings>().automaticAnalysis
    }

    override fun disposeUIResources() {
        callHints = null
        automaticAnalysis = null
    }
}
