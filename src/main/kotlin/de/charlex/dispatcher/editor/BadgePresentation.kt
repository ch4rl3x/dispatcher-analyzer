package de.charlex.dispatcher.editor

import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.ui.ColorUtil
import de.charlex.dispatcher.analysis.FileAnalysis
import de.charlex.dispatcher.model.BadgeSegment
import de.charlex.dispatcher.model.Dispatcher
import de.charlex.dispatcher.model.EffectSummary

internal object BadgePresentation {
    fun segments(summary: EffectSummary, declaration: Boolean): List<BadgeSegment> {
        val segments = summary.badgeSegments().map { segment ->
            if (segment.dispatcher == Dispatcher.Inherited) {
                BadgeSegment(null, "Unknown", false)
            } else {
                segment.copy(partial = !declaration && segment.partial)
            }
        }.distinct()
        return segments.ifEmpty { listOf(BadgeSegment(null, "Unknown", false)) }
    }

    fun create(
        factory: PresentationFactory,
        editor: Editor,
        summary: EffectSummary,
        tooltip: String,
        declaration: Boolean,
    ): InlayPresentation {
        val dark = ColorUtil.isDark(editor.colorsScheme.defaultBackground)
        val parts = mutableListOf(factory.smallTextWithoutBackground("Dispatcher "))
        segments(summary, declaration).forEachIndexed { index, segment ->
            if (index > 0) parts += factory.smallTextWithoutBackground(" | ")
            val color = BadgeColor.forDispatcher(segment.dispatcher)
            val text = segment.label + if (segment.partial) " (partial)" else ""
            parts += ColoredTextPresentation(factory.smallTextWithoutBackground(text), color.color(dark))
        }
        val badge = factory.roundWithBackgroundAndSmallInset(factory.seq(*parts.toTypedArray()))
        return factory.withTooltip(tooltip, badge)
    }

    fun render(
        factory: PresentationFactory,
        result: FileAnalysis,
        editor: Editor,
        settings: DispatcherHintSettings,
        sink: InlayHintsSink,
    ) {
        val length = editor.document.textLength
        result.declarations.forEach { (offset, badge) ->
            ProgressManager.checkCanceled()
            if (offset !in 0..length) return@forEach
            val presentation = create(factory, editor, badge.summary, badge.tooltip, true)
            val indent = editor.offsetToLogicalPosition(offset).column
            sink.addBlockElement(
                offset,
                relatesToPrecedingText = false,
                showAbove = true,
                priority = 0,
                presentation = factory.seq(factory.textSpacePlaceholder(indent, false), presentation),
            )
        }
        if (settings.showCalls) {
            result.calls.forEach { (offset, badge) ->
                ProgressManager.checkCanceled()
                if (offset !in 0..length) return@forEach
                val presentation = create(factory, editor, badge.summary, badge.tooltip, false)
                sink.addInlineElement(offset, true, factory.inset(presentation, left = 4), false)
            }
        }
    }
}
