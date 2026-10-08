package de.charlex.dispatcher.editor

import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.StaticDelegatePresentation
import com.intellij.codeInsight.hints.presentation.WithAttributesPresentation
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Graphics2D

internal class ColoredTextPresentation(presentation: InlayPresentation, val color: Color) :
    StaticDelegatePresentation(unstyled(presentation)) {

    override fun paint(g: Graphics2D, attributes: TextAttributes) {
        super.paint(g, attributes.clone().apply { foregroundColor = color })
    }

    companion object {
        // The factory's default text attributes would overwrite the dispatcher color.
        private fun unstyled(presentation: InlayPresentation): InlayPresentation =
            if (presentation is WithAttributesPresentation) presentation.presentation else presentation
    }
}
