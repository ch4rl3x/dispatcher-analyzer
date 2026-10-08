package de.charlex.dispatcher.analysis

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class DispatcherAnalysisStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<DispatcherAnalysisService>().startAnalysis()
    }
}
