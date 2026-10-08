package de.charlex.dispatcher.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.options.SearchableConfigurable
import java.awt.Component
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.DefaultListCellRenderer

class DispatcherConfigurable : SearchableConfigurable {
    private var callSiteMode: JComboBox<CallSiteBadgeMode>? = null
    private var onlyInFunction: JCheckBox? = null

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
        val onlyFunction = JCheckBox("Only in the function containing the caret").apply {
            isSelected = settings.onlyInFunctionContainingCaret
            isEnabled = mode.selectedItem != CallSiteBadgeMode.NONE
        }
        onlyInFunction = onlyFunction
        mode.addActionListener { onlyFunction.isEnabled = mode.selectedItem != CallSiteBadgeMode.NONE }
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("Call-site badges:").apply { labelFor = mode })
            add(mode)
        })
        add(onlyFunction.apply { alignmentX = Component.LEFT_ALIGNMENT })
    }

    override fun isModified(): Boolean {
        val settings = service<DispatcherSettings>()
        return callSiteMode?.selectedItem?.let { it != settings.callSiteMode } == true ||
            onlyInFunction?.isSelected?.let { it != settings.onlyInFunctionContainingCaret } == true
    }

    override fun apply() {
        val calls = callSiteMode?.selectedItem as? CallSiteBadgeMode ?: return
        val onlyFunction = onlyInFunction?.isSelected ?: false
        service<DispatcherSettings>().update(calls, onlyFunction)
    }

    override fun reset() {
        val settings = service<DispatcherSettings>()
        callSiteMode?.selectedItem = settings.callSiteMode
        onlyInFunction?.apply {
            isSelected = settings.onlyInFunctionContainingCaret
            isEnabled = settings.callSiteMode != CallSiteBadgeMode.NONE
        }
    }

    override fun disposeUIResources() {
        callSiteMode = null
        onlyInFunction = null
    }
}
