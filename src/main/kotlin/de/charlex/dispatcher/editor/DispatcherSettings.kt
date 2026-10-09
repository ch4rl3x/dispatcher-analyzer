package de.charlex.dispatcher.editor

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "DispatcherAnalyzerSettings", storages = [Storage("dispatcher-analyzer.xml")])
class DispatcherSettings : PersistentStateComponent<DispatcherHintSettings> {
    @Volatile private var stored = DispatcherHintSettings()

    val callSiteMode: CallSiteBadgeMode
        get() = stored.resolvedCallSiteMode()

    val showCalls: Boolean
        get() = callSiteMode != CallSiteBadgeMode.NONE

    val onlyInFunctionContainingCaret: Boolean
        get() = stored.onlyInFunctionContainingCaret

    val showNonSuspendDeclarations: Boolean
        get() = stored.showNonSuspendDeclarations

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
        update(mode, stored.onlyInFunctionContainingCaret)
    }

    fun update(
        mode: CallSiteBadgeMode,
        onlyInFunctionContainingCaret: Boolean,
        showNonSuspendDeclarations: Boolean = stored.showNonSuspendDeclarations,
    ) {
        val next = DispatcherHintSettings(
            mode != CallSiteBadgeMode.NONE, mode, onlyInFunctionContainingCaret, showNonSuspendDeclarations,
        )
        if (stored == next) return
        stored = next
        InlayRefresh.restartOpenProjects()
    }

    fun updateOnlyInFunctionContainingCaret(enabled: Boolean) {
        update(callSiteMode, enabled)
    }
}
