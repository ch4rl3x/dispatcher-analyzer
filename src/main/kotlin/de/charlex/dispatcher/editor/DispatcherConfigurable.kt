package de.charlex.dispatcher.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.options.SearchableConfigurable
import java.awt.Component
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.DefaultListCellRenderer

class DispatcherConfigurable : SearchableConfigurable {
    private var callSiteMode: JComboBox<CallSiteBadgeMode>? = null
    private var automaticAnalysis: JCheckBox? = null

    override fun getId() = "dispatcher.analyzer"

    override fun getDisplayName() = "Dispatcher Analyzer"

    override fun createComponent(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        val settings = service<DispatcherSettings>()
        val mode = JComboBox(CallSiteBadgeMode.entries.toTypedArray()).apply {
            selectedItem = settings.callSiteMode
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component = super.getListCellRendererComponent(
                    list,
                    (value as? CallSiteBadgeMode)?.displayName.orEmpty(),
                    index,
                    isSelected,
                    cellHasFocus,
                )
            }
        }
        callSiteMode = mode
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("Call-site badges:").apply { labelFor = mode })
            add(mode)
        })
        val automatic = JCheckBox("Analyze automatically after typing pauses", settings.automaticAnalysis)
        automaticAnalysis = automatic
        add(automatic)
    }

    override fun isModified(): Boolean {
        val settings = service<DispatcherSettings>()
        return callSiteMode?.selectedItem?.let { it != settings.callSiteMode } == true ||
            automaticAnalysis?.isSelected?.let { it != settings.automaticAnalysis } == true
    }

    override fun apply() {
        val calls = callSiteMode?.selectedItem as? CallSiteBadgeMode ?: return
        val automatic = automaticAnalysis ?: return
        service<DispatcherSettings>().update(calls, automatic.isSelected)
    }

    override fun reset() {
        callSiteMode?.selectedItem = service<DispatcherSettings>().callSiteMode
        automaticAnalysis?.isSelected = service<DispatcherSettings>().automaticAnalysis
    }

    override fun disposeUIResources() {
        callSiteMode = null
        automaticAnalysis = null
    }
}
