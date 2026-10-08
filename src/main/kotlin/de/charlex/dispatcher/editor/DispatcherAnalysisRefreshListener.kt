package de.charlex.dispatcher.editor

import com.intellij.openapi.project.Project
import de.charlex.dispatcher.analysis.DispatcherAnalysisListener

class DispatcherAnalysisRefreshListener(private val project: Project) : DispatcherAnalysisListener {
    override fun analysisUpdated() {
        InlayRefresh.restartProject(project, "Dispatcher analysis completed")
    }
}
