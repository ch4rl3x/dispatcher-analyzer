package de.charlex.dispatcher.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsScheme

class DispatcherColorSchemeListener : EditorColorsListener {
    override fun globalSchemeChange(scheme: EditorColorsScheme?) {
        ApplicationManager.getApplication().invokeLater {
            InlayRefresh.restartOpenProjects()
        }
    }
}
