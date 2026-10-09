package de.charlex.dispatcher.editor

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import de.charlex.dispatcher.model.EffectSummary

internal object BadgeTooltip {
    fun create(summary: EffectSummary, details: String, declaration: Boolean, darkTheme: Boolean): String = buildString {
        val action = when {
            declaration -> "is called from"
            summary.setsDispatcher -> "switches to"
            else -> "runs on"
        }
        append("<html>This function $action Dispatcher ")
        val contexts = if (!declaration && summary.setsDispatcher) EffectSummary(summary.selectedDispatchers) else summary
        val entries = BadgePresentation.segments(contexts, declaration = true)
        entries.forEachIndexed { index, segment ->
            if (index > 0) append(if (index == entries.lastIndex) " and " else ", ")
            val color = BadgeColor.forDispatcher(segment.dispatcher).color(darkTheme)
            append("<span style=\"color: #${ColorUtil.toHex(color)}\">")
            append(StringUtil.escapeXmlEntities(segment.label))
            append("</span>")
        }
        append('.')
        if (details.isNotBlank()) {
            append("<br><br>")
            append(StringUtil.escapeXmlEntities(details.trim()))
        }
        append("</html>")
    }
}
