package de.charlex.dispatcher.editor

import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage

class DispatcherColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName() = "Dispatcher Analyzer"
    override fun getIcon() = null
    override fun getHighlighter() = PlainSyntaxHighlighter()
    override fun getAttributeDescriptors() = emptyArray<AttributesDescriptor>()
    override fun getColorDescriptors() = arrayOf(
        descriptor("Main", BadgeColor.MAIN),
        descriptor("Default", BadgeColor.DEFAULT),
        descriptor("IO", BadgeColor.IO),
        descriptor("Unknown and analysis problems", BadgeColor.UNKNOWN),
        descriptor("Other identified dispatchers", BadgeColor.CUSTOM),
    )

    override fun getAdditionalHighlightingTagToDescriptorMap() = null
    override fun getAdditionalHighlightingTagToColorKeyMap() = BadgeColor.entries.associate { it.name to it.key }
    override fun getDemoText() = """
        Dispatcher.<MAIN>Main</MAIN>
        Dispatcher.<DEFAULT>Default</DEFAULT>
        Dispatcher.<IO>IO</IO>
        Dispatcher.<UNKNOWN>Unknown</UNKNOWN>
        Dispatcher.<CUSTOM>Unconfined</CUSTOM> | <CUSTOM>Custom worker</CUSTOM>

        Dispatcher.<MAIN>Main (partial)</MAIN> | <IO>IO (partial)</IO>
    """.trimIndent()

    private fun descriptor(label: String, color: BadgeColor) =
        ColorDescriptor(label, color.key, ColorDescriptor.Kind.FOREGROUND)
}
