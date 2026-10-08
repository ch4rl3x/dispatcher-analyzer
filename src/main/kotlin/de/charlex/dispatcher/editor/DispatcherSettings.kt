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

    val callSiteMode: CallSiteBadgeMode
        get() = stored.resolvedCallSiteMode()

    val showCalls: Boolean
        get() = callSiteMode != CallSiteBadgeMode.NONE

    val automaticAnalysis: Boolean
        get() = stored.automaticAnalysis

    override fun getState(): DispatcherHintSettings =
        stored.copy(showCalls = showCalls, callSiteMode = callSiteMode)

    override fun loadState(state: DispatcherHintSettings) {
        val mode = state.resolvedCallSiteMode()
        stored = state.copy(showCalls = mode != CallSiteBadgeMode.NONE, callSiteMode = mode)
    }

    fun updateCalls(showCalls: Boolean) {
        updateCalls(if (showCalls) CallSiteBadgeMode.ALL else CallSiteBadgeMode.NONE)
    }

    fun updateCalls(mode: CallSiteBadgeMode) {
        update(mode, stored.automaticAnalysis)
    }

    fun update(showCalls: Boolean, automaticAnalysis: Boolean) {
        update(if (showCalls) CallSiteBadgeMode.ALL else CallSiteBadgeMode.NONE, automaticAnalysis)
    }

    fun update(callSiteMode: CallSiteBadgeMode, automaticAnalysis: Boolean) {
        val next = DispatcherHintSettings(callSiteMode != CallSiteBadgeMode.NONE, automaticAnalysis, callSiteMode)
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
