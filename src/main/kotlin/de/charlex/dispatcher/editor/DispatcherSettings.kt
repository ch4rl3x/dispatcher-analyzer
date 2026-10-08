package de.charlex.dispatcher.editor

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectManager
import de.charlex.dispatcher.analysis.DispatcherAnalysisService

@Service(Service.Level.APP)
@State(name = "DispatcherAnalyzerSettings", storages = [Storage("dispatcher-analyzer.xml")])
class DispatcherSettings : PersistentStateComponent<DispatcherHintSettings> {
    @Volatile private var stored = DispatcherHintSettings()

    val showCalls: Boolean
        get() = stored.showCalls

    val automaticAnalysis: Boolean
        get() = stored.automaticAnalysis

    override fun getState(): DispatcherHintSettings = stored.copy()

    override fun loadState(state: DispatcherHintSettings) {
        stored = state.copy()
    }

    fun updateCalls(showCalls: Boolean) {
        update(showCalls, stored.automaticAnalysis)
    }

    fun update(showCalls: Boolean, automaticAnalysis: Boolean) {
        val next = DispatcherHintSettings(showCalls, automaticAnalysis)
        if (stored == next) return
        val modeChanged = stored.automaticAnalysis != automaticAnalysis
        stored = next
        if (modeChanged) {
            ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.forEach { project ->
                project.service<DispatcherAnalysisService>().analysisModeChanged()
            }
        }
        InlayRefresh.restartOpenProjects()
    }
}
