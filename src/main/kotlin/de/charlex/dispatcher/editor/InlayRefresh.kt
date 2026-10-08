package de.charlex.dispatcher.editor

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.hints.InlayHintsSwitch
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

internal object InlayRefresh {
    private val switches = ExtensionPointName.create<InlayHintsSwitch>("com.intellij.codeInsight.inlayHintsSwitch")

    fun restartOpenProjects() {
        ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.forEach { project ->
            restartProject(project, "Dispatcher Analyzer settings changed")
        }
    }

    fun restartProject(project: Project, reason: String) {
        if (project.isDisposed) return
        // Reapplying each switch preserves its preference and invalidates native hint caches.
        switches.extensionList.forEach { switch -> switch.setEnabled(project, switch.isEnabled(project)) }
        DaemonCodeAnalyzer.getInstance(project).restart(reason)
    }
}
